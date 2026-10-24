# Architecture

## Modules

```
:protocol  pure Kotlin/JVM. Codecs, state machines, reducers, clients over abstract transports.
  net/      Connection, TcpConnector, TlsConnector (TOFU pin), CertificatePin, LanAddress, LineSplitter, Redact
  theatre/  soundbar TCP 33336: TheatreCodec, TheatreReducer (optimistic), TheatreClient
  atv/      Android TV Remote v2: Wire protos, Framing, PairingHash, PairingMachine, AtvPairing,
            RemoteSession, AtvRemote, VoiceSession, KeyMap, AppCatalog, ClientCertificates
  bravia/   REST JSON-RPC + PSK: JsonRpc, BraviaParsers, Capabilities, BraviaClient, Ircc
  grpc/     Sony-cloud plane: interface, field paths, PKCE, UnavailableSonyCloudPlane
  scenes/   Scene model, SceneEngine (plan / match / capture), SceneRunner, StarterScenes
:app       Android: Compose UI, Hilt, DataStore, Keystore secrets, socket/TLS/OkHttp transports
```

`:app` owns sockets, TLS, HTTP, reconnection and lifecycles; `:protocol` owns bytes and state. Every client exposes a `StateFlow`; every write updates state before the network round trip, so the UI and haptics respond within a frame.

## Protocols

### 1. Android TV Remote v2 (TV remote, keyboard, foreground app)

- Pairing on TLS 6467, remote on TLS 6466. The client presents a self-signed RSA 2048 certificate; the TV binds the pairing to it, so it is generated once and reused.
- Frames: protobuf varint length prefix + message (`Framing`, `FrameDecoder`, robust to split and merged reads).
- Pairing: Request → Ack, Option → Option, Configuration → Ack, the TV shows a 6-hex code, Secret → SecretAck. Secret = SHA-256(client modulus ‖ client exponent ‖ server modulus ‖ server exponent ‖ last two code bytes), numbers as unsigned big-endian bytes; the first hash byte must equal the first code byte (typos caught locally).
- Session: the TV sends configure / set-active / pings (~5 s) which the client answers; then key injects (short / long press), IME batch edits (`ime_counter`, `field_counter` from the TV), app links (`market://launch?id=<package>`). The TV reports `RemoteImeShowRequest` (focused field, text, label), `RemoteImeKeyInject.app_info.app_package` (foreground app), power and volume.
- Key codes are Android `KeyEvent` codes; `KeyMap` maps them and the hardware keyboard.

#### Voice (hold to talk to the TV's assistant)

Reverse-engineered by [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) ([`remote.py`](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py), [`remotemessage.proto`](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto), [`demo.py`](https://github.com/tronikos/androidtvremote2/blob/main/src/demo.py)); no official documentation exists. `VoiceSession` implements it from these facts (no code copied):

- Feature mask: bits `PING 1, KEY 2, IME 4, VOICE 8, POWER 32, VOLUME 64, APP_LINK 512`. androidtvremote2 answers the TV's `remote_configure` with its own mask ANDed with the TV's `code1`, and `remote_set_active` with its mask; voice is enabled only when bit 8 survives. Our fixed 622 already carries bit 8; tutti keeps the TV's `code1` (`RemoteState.tvFeatures`) and, when bit 8 is absent, does not try.
- Messages (field numbers from that proto): `RemoteVoiceBegin { int32 session_id = 1; string package_name = 2 }`, `RemoteVoicePayload { int32 session_id = 1; bytes samples = 2 }`, `RemoteVoiceEnd { int32 session_id = 1 }`, at `RemoteMessage` fields 30, 31, 32.
- Sequence: the client sends `KEYCODE_SEARCH` (84, not `KEYCODE_VOICE_ASSIST`); the TV answers `remote_voice_begin { session_id }` (the TV fills `package_name`); the client echoes `remote_voice_begin { session_id }` ("required by the protocol"); then payloads; then `remote_voice_end { session_id }`, with no reply awaited. androidtvremote2 gives the TV 2 s to answer.
- Audio: 16-bit PCM, 8 kHz, mono ("Default audio sample payload ... split into 20 KB messages"; the audio configuration in `RemoteVoiceBegin` is unknown). Payloads at most 20 480 bytes; a shorter piece is padded with zero bytes to 8 192. No pacing or acknowledgement: chunks go as they are captured (the demo's buffers are ~1 s). The demo caps a stream at 10 s; tutti caps at 15 s and closes the session itself.
- Community notes, single devices, unverified here: on a TCL TV 8 KiB messages were ignored and 20 KiB worked; the TV detects end of speech itself ([azylman/mirrormere#607](https://github.com/azylman/mirrormere/issues/607)); an older report says some TVs close the connection after payloads ([XDA](https://xdaforums.com/t/voice-input-in-android-tv-remote-control.4712015/)).
- Fallback when the TV does not offer voice: the search key alone opens the TV's own search screen, where the physical remote's mic or tutti's keyboard can take over; tutti says so.
- tutti's flow: the phone mic opens on press (before the TV answers; up to 15 s of audio is held meanwhile), full 20 KiB chunks stream as they fill, release sends the padded remainder and the end; sliding off, backgrounding or a mic error sends the end without the remainder. A begin that arrives after a cancel or a timeout is closed with an end. Phones whose mic refuses 8 kHz record 16 kHz and `VoicePcm.halve` averages pairs (crude low-pass, no plain decimation).

### 2. Soundbar IP control, TCP 33336

Newline-delimited JSON `{"id","type":"get|set","feature","value"}` → `result` (feature + value for `get`, `"ACK"` for `set`) and unsolicited `notify`. No authentication; one persistent socket; requires "External control" in BRAVIA Connect. DRC and Auto Volume do not notify and are polled. The reducer shows a `set` at once, confirms on ACK or a matching notify (whichever comes first), and rolls back on refusal, timeout (3 s) or disconnect. A `get` answered `ERR` or `NAK` is a refusal, never a value: the feature goes in `TheatreState.refused`, and the reducer test holds that.

Réglages › Réglages système de la barre is probe-gated row by row: a setting shows only once the bar has reported a value for it (`TheatreState.supports`, a `get` answer or a notify), so what the Bar 9 lacks stays hidden instead of greyed. `refreshAll` asks every feature at connect; the diagnostic report lists the refused keys, never values. tutti never asks for `system.serialnumber`, `network.macaddress` or `network.ipaddress`, and never touches `system.externalcontrol` (turning it off would cut tutti's own socket): `TheatreFeature.NEVER_ASKED`, tested.

### 3. BRAVIA REST, JSON-RPC + PSK

`POST /sony/{system|audio|avContent|appControl|video|videoScreen}` with `X-Auth-PSK` (HTTP port 80, or HTTPS port 443 on EU models under RED-DA). Discovery runs first: `guide.getSupportedApiInfo` (`/sony/guide`, no auth, the documented call), falling back to `getMethodTypes` per service (not in Sony's reference); the settings UI shows only what the TV lists, and calls to unlisted methods fail fast. Picture (`get/setPictureQualitySettings`), sound output (`get/setSoundSettings`), speaker settings, inputs (`getCurrentExternalInputsStatus`, `setPlayContent`), apps (`getApplicationList`, `setActiveApp`), power and power saving, Wake-on-LAN mode (`get/setWolMode`), scene select (`videoScreen.get/setSceneSetting`), system information, volume information, IRCC (`/sony/ircc` SOAP, codes from `getRemoteControllerInfo`). When `setSoundSettings` is listed without `getSoundSettings`, the output is a write-only choice of the four documented `outputTerminal` values (`speaker`, `speaker_hdmi`, `hdmi`, `audioSystem`). A range is a lone candidate with max above min: Sony sends -1 for min, max and step on non-numeric targets. Réglages › TV opens whenever a TV is paired; while REST is not ONLINE it explains how to turn IP control on (distinct text for a refused PSK) instead of showing dead rows. EU units may serve HTTPS only: the transport pins each host's certificate on first use.

### 4. Sony-cloud gRPC (opt-in "Connexion Sony (expérimental)")

Bar on h2c port 55051, TV on a port advertised by mDNS `_sonysmarthome._tcp`; Sony Seeds OAuth (PKCE, Custom Tab), ~24 h session keys, HMAC-signed handshake, `GetStatesWithAuth`, `ExecCommandWithAuth`, `StartNotifyStates` over dotted field paths. The request envelope (`serialized_request + hmac`), the HMAC input and the Seeds OAuth endpoints are not documented precisely enough to implement without guessing, so `:protocol` stops at `SonyCloudPlane` and `:app` binds `UnavailableSonyCloudPlane` ("non disponible"). Failures there never touch TCP/REST.

### 5. Out of reach

Acoustic Center Sync, sound calibration, speaker placement and first setup: a shortcut opens the TV's sound settings (v2/IRCC keys), and "Recalibrer dans BRAVIA Connect" deep-links to Sony's app.

## Verified vs not

| Item | Status |
| --- | --- |
| v2 protos, framing, pairing flow and hash | Working in kud/androidtv-remote and kud/gtv against Google TV devices; **not yet run against the XR80** |
| Foreground app via `RemoteImeKeyInject` | Working in kud/gtv; XR80 unverified |
| IME show request / batch edit counters | Message shapes from the proto; the live mirror flow is **unverified** anywhere; field focus loss is inferred (app change) |
| Soundbar TCP 33336 vocabulary | Verified by bravia-quad-homeassistant on Theatre Quad; Bar 9 by one community report only |
| Voice Zoom 3 (`audio.voicezoom3*`) | **Unverified** on Bar 9: shown only if a `get` probe answers |
| `bluetooth.mode` values | Undocumented: displayed raw, never set as a setting. The only write is pairing (below) |
| Soundbar system settings (source: [bravia-quad-homeassistant](https://github.com/steamEngineer/bravia-quad-homeassistant), `custom_components/bravia_quad/const.py`, `select.py`, `switch.py`, `button.py`, `docs/tcp-protocol.md`, `docs/grpc-tcp-mapping.md`, read 2026-10-23) | Every row below is **unverified on the Bar 9** and shown only if the bar answers it |
| `hdmi.cec` on/off, `system.autostandby` on/off | Documented in its `tcp-protocol.md`, both seen as notifies on the Quad |
| `hdmi.audioreturnchannel` `off`/`arc`/`earc` | Its TCP select; ARC vs eARC is negotiated with the TV (its mapping doc: TCP may say `arc` while the cloud says on) |
| `hdmi.standbylink` `auto`/`on`/`off` | Its label is "HDMI standby through" (gRPC `system_setting.hdmi_standby_through`, flagged `verified=False` there); tutti labels it Transmission en veille / Standby through |
| `hdmi.passthrough` `auto`/`on`/`off` | Its label is "HDMI signal pass through"; no gRPC equivalent confirmed (its parity gaps) |
| `system.netbtstandby` on/off | Its switch "Network/Bluetooth standby" |
| `system.autoupdate` on/off | Its switch, `verified=False` there |
| `system.modeltype`, `system.version` | Its TCP client reads these for the device registry. `system.modelname` is the HTTP plane (`:54545/fcgi-bin/request.fcgi`), not this port: tutti read the wrong key until 2026-10-23 |
| Bluetooth pairing | Its "Bluetooth pairing" button (`button.py`): `main.input` `bluetooth` if needed, `bluetooth.mode` `Off`, 500 ms, `RX`. Observed on the Quad only; offered only when the bar reports `bluetooth.mode`. `BluetoothPairing`, tested |
| Firmware update status / install | **Not on this port**: that integration polls the HTTP management API (`:54545`, `async_check_firmware_update`). Not implemented; the auto-update switch is the only update control |
| Connected Bluetooth device, codec, signal | **Not on this port**: gRPC only (`playback_control.bt_device_name`, `bt_codec`, `bt_signal_strength`). Not implemented |
| `system.serialnumber`, `network.macaddress` | Exist on this port; tutti never asks (`NEVER_ASKED`) |
| BRAVIA REST methods, services, versions, params, result shapes and auth levels | Verified against Sony's reference (pro-bravia.sony.net/remote-display-control/rest-api/reference, 2026-10-22), **still unverified on the XR80**, hence capability-driven UI. Auth none: `getInterfaceInformation`, `getPowerStatus`, `getPowerSavingMode`, `getRemoteControllerInfo`, `getVolumeInformation`, `getSpeakerSettings`, `getPictureQualitySettings`, `getCurrentExternalInputsStatus`; generic: every setter used; private: `getSystemInformation`, `getApplicationList`, `getPlayingContentInfo` |
| `getMethodTypes`, `getSoundSettings` | Not in Sony's reference (only `setSoundSettings` 1.1 is): kept as fallback / capability-gated, **unverified** |
| `getWolMode` / `setWolMode` (system 1.0, generic), `getSceneSetting` (videoScreen 1.0, none) / `setSceneSetting` (generic) | In Sony's reference (2026-10-23); scene select is documented as "BZ40P / BZ35P / BZ30P: not supported", i.e. consumer models; XR80 **unverified**. That WoL mode is the same switch as the menu's "Remote start" is **unverified** |
| Sleep timer | **Not in Sony's REST reference.** tutti offers it only if `getRemoteControllerInfo` lists an IRCC key named `SleepTimer` or `Sleep`; the name on the XR80 is **unverified** |
| `getSettings` / `setSettings` / `getSettingsSchema`, `get/setLEDIndicatorStatus`, `get/setScreenRotation`, `requestReboot` | In the reference but pro-display (BZ, localhost only) or not a BRAVIA Connect setting: not used |
| Error codes (3, 5, 7, 12, 14, 15, 401, 403, 404, 501, 40000, 40004, 40005) | Verified against the reference's common error table |
| IRCC (`/sony/ircc`, `SOAPACTION "urn:schemas-sony-com:service:IRCC:1#X_SendIRCC"`, `text/xml; charset=UTF-8`, `X-Auth-PSK`) | Path and headers verified against the IRCC-IP page; the envelope body is not printed there and stays the community form |
| PSK setup on the TV | Reference: Settings › Network & Internet › Local network (also written "Home network") › IP control › Authentication, "Normal and Pre-Shared Key" lets PSK clients reach generic/private methods; Remote device settings › Control remotely must be on (off by default on EU RED-DA models, which also take a 16 to 20 character key and HTTPS only). Pro-display wording: the consumer menu labels and French strings are unverified |
| Power on over REST | `setPowerStatus(true)` works only from sleep, not suspend, and needs Remote start / Control remotely on; from suspend only Wake-on-LAN (UDP 7 or 9) helps. tutti learns the MAC once REST is online (`getNetworkSettings`, the interface carrying the TV's address, else `getSystemInformation.macAddr`), keeps it beside the host in the non-secret store, and on Power with the TV out of reach sends the magic packet to UDP 9 (the TV, plus the subnet's broadcasts when the phone is on its IPv4 subnet; private addresses only, `WakeOnLan.targets`), then waits up to 12 s for the TV to answer before sending power on. Without a PSK the MAC is never learnt (`getNetworkSettings` needs auth; whether `getSystemInformation` answers without one is unverified), and no PSK-free source is usable on Android 11+: the ARP table is closed to apps from API 29, and the mDNS/DIAL/UPnP adverts carry no NIC MAC we can rely on. The one documented PSK-free route, not built: Sony's PIN registration ("Normal" auth; the TV shows a 4-digit code, `accessControl.actRegister` asks for the WOL function, then `getSystemInformation.macAddr`, as pybravia does), at the cost of an onboarding step and a cookie stored as a secret. A failed wake says which case it was (`WakeAttempt`: MAC not learnt, nothing at the address, or asleep) and the diagnostic gives the MAC as known or not, packets sent, and the remote's last connect class (timeout/no route = nothing answered, refused = host up). Which MAC an XR80 on Wi-Fi reports, and whether it wakes from suspend, are **unverified** |
| Sony picture mode values in starter scenes (`cinema`, `game`, `standard`) | Unverified; the editor offers the TV's own candidates |
| Sony-cloud gRPC | Interface only, nothing on the wire |
| v2 voice (search key → begin → 8 kHz PCM payloads → end) | Facts from androidtvremote2 (Python, Home Assistant's library); **never run by tutti against any TV**, the XR80 included. Whether the BRAVIA's assistant (Gemini or Google Assistant) accepts it, and whether 20 KiB chunks give a live transcript, are open |
| Wire 7.1.0, Hilt 2.58, KSP 2.3.12, Kotlin 2.3.20 | Tags verified upstream; AGP 8.13.0 and AndroidX versions chosen without access to Google Maven, settled by CI |

## Diagnostic (Réglages › Diagnostic)

`:protocol`'s `DiagnosticRunner` walks every plane against the configured devices with its own read-only connections: mDNS discovery; REST `getInterfaceInformation` (no PSK), `getSystemInformation` (PSK), `getMethodTypes`, then only the getters the TV lists; the v2 remote's TLS connect and handshake (it answers configure, set-active and ping, and sends nothing else); the pairing port's TCP reachability; a `get` for every soundbar feature, Voice Zoom 3 included. Each step records ok / failed / skipped, its time, a short error class and what the device reported. `DiagnosticReport` renders the plain-text report, scrubbed through `Redact.text` with the hosts, PSK, pin and every identifying value seen in a reply (`JsonShape` keeps keys and types, values only for harmless keys). Pins seen during a run are never saved.

## Threat model

Assets: the TV PSK, the TV pairing (client private key + TV pin), Sony OAuth tokens and session keys (opt-in), and control of the living-room devices.

| Threat | Mitigation |
| --- | --- |
| Someone on the LAN impersonates the TV to capture the PSK or a pairing | TLS to the TV is pinned per device on first use (`CertificatePin`); a changed certificate fails the handshake and asks for re-pairing. Pairing requires the code shown on the real TV screen. |
| LAN eavesdropping on REST over HTTP | Prefer HTTPS with a pin; HTTP is allowed only to private addresses (`LanAddress`). The PSK only grants TV control on the LAN, never account access. |
| The soundbar port has no authentication | Inherent to the device: anyone on the LAN can already control it. The app adds nothing exploitable and exposes no listening port. |
| Secrets leak through backups, logs or crash reports | Encrypted with an AndroidKeyStore key, stored in two paths excluded from cloud backup and device transfer; `Redact` and header-free logging; `SessionKeys.toString` is redacted. |
| Client key extraction from a stolen phone | The TLS key is generated non-exportable in AndroidKeyStore where the TLS stack allows; otherwise (fallback) encrypted at rest. Device lock protects the rest. |
| Sony account compromise via the app | Sign-in is opt-in, OAuth PKCE in a Custom Tab (the app never sees the password, no WebView), tokens scoped to device control and wiped by "Se déconnecter de Sony". |
| Malicious or malformed device traffic | Every decoder is total: bad lines and frames become `Malformed` or close the connection; frame size is bounded (1 MiB), line length bounded (64 KiB). |
| A tampered APK update | Releases are signed with one long-lived key held only in GitHub secrets; Android refuses updates signed otherwise. The CI job never publishes unsigned. |

Out of scope: a compromised phone OS, a compromised router, physical access to the TV.
