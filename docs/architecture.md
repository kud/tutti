# Architecture

## Modules

```
:protocol  pure Kotlin/JVM. Codecs, state machines, reducers, clients over abstract transports.
  net/      Connection, TcpConnector, TlsConnector (TOFU pin), CertificatePin, LanAddress, LineSplitter, Redact
  theatre/  soundbar TCP 33336: TheatreCodec, TheatreReducer (optimistic), TheatreClient
  atv/      Android TV Remote v2: Wire protos, Framing, PairingHash, PairingMachine, AtvPairing,
            RemoteSession, AtvRemote, KeyMap, AppCatalog, ClientCertificates
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

### 2. Soundbar IP control, TCP 33336

Newline-delimited JSON `{"id","type":"get|set","feature","value"}` → `result` (feature + value for `get`, `"ACK"` for `set`) and unsolicited `notify`. No authentication; one persistent socket; requires "External control" in BRAVIA Connect. DRC and Auto Volume do not notify and are polled. The reducer shows a `set` at once, confirms on ACK or a matching notify (whichever comes first), and rolls back on refusal, timeout (3 s) or disconnect.

### 3. BRAVIA REST, JSON-RPC + PSK

`POST /sony/{system|audio|avContent|appControl|video}` with `X-Auth-PSK`. `getMethodTypes` runs first per service; the settings UI shows only what the TV lists, and calls to unlisted methods fail fast. Picture (`get/setPictureQualitySettings`), sound output (`get/setSoundSettings`), speaker settings, inputs (`getCurrentExternalInputsStatus`, `setPlayContent`), apps (`getApplicationList`, `setActiveApp`), power and power saving, volume information, IRCC (`/sony/ircc` SOAP, codes from `getRemoteControllerInfo`). EU units may serve HTTPS only: the transport pins each host's certificate on first use.

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
| `bluetooth.mode` values | Undocumented: displayed raw, never set |
| BRAVIA REST method list and versions | From memory of Sony's public reference (site unreachable): **unverified on XR80**, hence capability-driven UI |
| Sony picture mode values in starter scenes (`cinema`, `game`, `standard`) | Unverified; the editor offers the TV's own candidates |
| Sony-cloud gRPC | Interface only, nothing on the wire |
| Wire 7.1.0, Hilt 2.58, KSP 2.3.12, Kotlin 2.3.20 | Tags verified upstream; AGP 8.13.0 and AndroidX versions chosen without access to Google Maven, settled by CI |

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
