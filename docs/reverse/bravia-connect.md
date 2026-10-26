# Reverse map: everything BRAVIA Connect reaches, and how tutti could

Maya, 2026-10-24. Phase 1 of the "tout faire sans BRAVIA Connect" effort (Erwann, 2026-10-24). Research only: nothing here is implemented, and nothing was run against Erwann's devices. Every row is **unverified on the Bar 9 (HT-A9000) and the XR80** unless it says otherwise; it is what the sources below observed on neighbouring models.

§7 covers handing off to BRAVIA Connect (deep links); §8 is the coverage table for "les simples commandes comme les compliquées" (Erwann, 2026-10-24).

This reverses the earlier MVP line ("BRAVIA Connect kept for fine-tuning", architecture.md §5). That is Erwann's call; this doc says what it costs.

## Sources, and what was not reachable

| Source | What it gave | Read |
| --- | --- | --- |
| [steamEngineer/bravia-quad-homeassistant](https://github.com/steamEngineer/bravia-quad-homeassistant): `docs/reverse-engineering-bravia-connect.md`, `sony-grpc-reference.md`, `grpc-auth-lifecycle.md`, `grpc-tcp-mapping.md`, `seeds-cloud-states.md`, `tcp-protocol.md`; `custom_components/bravia_quad/const.py`, `grpc/all_field_paths.txt`, `grpc/get_states_auth.py`, `grpc/exec_command_request.py`, `grpc/credentials.py`, `bravia_http_client.py`; `scripts/grpc/http_54545_catalog.py` | Bar TCP keys, the bar's gRPC plane byte by byte (Frida captures of BRAVIA Connect 3.6.3 on an HT-A9M2 Theatre Quad, fw 001.454, cross-checked against gRPC server reflection by @mafredri in its issue #16), the Seeds cloud endpoints, the HTTP :54545 plane | 2026-10-24 |
| [braviafanboy/bravia-tv-grpc-homeassistant](https://github.com/braviafanboy/bravia-tv-grpc-homeassistant): `README.md`, `schema/grpc_field_paths.json`, `schema/grpc_capabilities.json`, `custom_components/bravia_tv_grpc/sensor.py`, `bravia_tv_client.py` | The TV's gRPC field schema with types and enums, signal readout shapes. Tested on a **BRAVIA 8 II (K-65XR8M2)** only; ours is the first-generation BRAVIA 8 | 2026-10-24 |
| sony-cisip2 0.1.0a1 (PyPI), `commandset.py` | Sony's AV-receiver CIS-IP2 vocabulary (185 keys: `main.*`, `zone2/3.*`, GUI, triggers). **No key for incoming audio format, channel count or sample rate.** `main.input` gets and notifies | 2026-10-24 (previous agent) |
| Sony's BRAVIA professional reference (pro-bravia.sony.net): REST API reference and structure, IRCC-IP, Simple IP Control | Every documented REST service, method and target | 2026-10-22, local copy |
| **BRAVIA Connect APK** (`jp.co.sony.hes.home`) | **Not obtained.** APKMirror answers with a Cloudflare challenge; APKPure, APKCombo, APKMonk and apk.support do not answer through the proxy; Google Play serves only the store page. No mirror, no static analysis. Retried for the manifest on 2026-10-24 (§7): APKPure's download host, APKCombo, Exodus Privacy and Sony's `/.well-known/assetlinks.json` hosts are refused by the session's proxy policy (403 on CONNECT); the Play page answers and lists 3.10.0 as the current version (the captures above are 3.6.3) | 2026-10-24 |

What the missing APK costs: BRAVIA Connect is a **Flutter** app (the Quad author had to hook Dart's `hmacApplyMac` through blutter because nothing went through native `libcrypto`). jadx would have shown the Android shell and little else; the field paths, endpoints and UI strings live in `libapp.so` (Dart AOT snapshot), which needs blutter, not jadx. So the "keys in the APK never shown in its UI" question cannot be answered from the APK in this environment. The substitute is better evidence anyway: **`GetCapabilities` on each device** (no authentication, below) returns the paths the firmware itself advertises, which is the real superset. Every "hidden" item below is "advertised by firmware or vocabulary, absent from BRAVIA Connect's visible UI (per its captures and screenshots)", not "found as a dead string in the APK".

No code, assets, client ids or keys were copied into tutti. Protocol facts only.

## The planes

| # | Plane | Device | Port / path | Auth | Push | tutti today |
| --- | --- | --- | --- | --- | --- | --- |
| A | CIS-IP2 JSON | Bar | TCP 33336 | none (needs "External control" on) | notify for most keys; DRC and Auto Volume polled | full client (`theatre/`) |
| B | HTTP management (FCGI) | Bar | HTTP 54545 `POST /fcgi-bin/request.fcgi`, body `{"type":"http_get","packet":[["<key>",…]]}` → `{"type":"http_get_result",…}` | **none** | no | optional Technical details / diagnostic readouts; unverified protocol on Bar 9 |
| C | `ControlDeviceService` gRPC, h2c | Bar | TCP 55051, cleartext HTTP/2 | Seeds session keys + per-request HMAC; `GetCapabilities` needs none | `StartNotifyStates` server stream | interface only (`SonyCloudPlane`), unbound |
| D | same service, h2c | TV | port from mDNS `_sonysmarthome._tcp` (dynamic, changes on reboot) | same as C | same | interface only |
| E | REST JSON-RPC | TV | `/sony/{guide,system,audio,avContent,appControl,video,videoScreen,encryption}` on 80/443 | none / PSK (generic, private) | no | used, capability-gated |
| F | IRCC SOAP | TV | `/sony/ircc` | PSK | no | used |
| G | Android TV Remote v2 | TV | TLS 6466/6467 | pairing code | power, volume, foreground app, IME | used |
| H | Simple IP Control | TV | TCP 20060, fixed 24-byte `*S…` frames | none (toggle in the TV menu) | some notifies | not used; pro-display doc, power/input/picture-mute only, no readouts beyond A–G |
| I | Sony Seeds cloud | both | `https://v1.api.auth.seeds.services` (OAuth), `https://v1.api.iot.seeds.services` (`/devices`, `/devices/{id}/session_keys`, `/devices/{id}/states`) | OAuth PKCE (Sony account) | no | `Pkce.kt` only |

## Sony connection: removing "not available"

Settings › "Sony connection (experimental)" reads *Not available · Sony's cloud channel is not documented well enough to implement without guessing*. This section is the documentation that row was waiting for, and its plan (end of section) is what turns it on.

### Can the envelope and HMAC now be pinned down? Yes, for the bar.

The reason `SonyCloudPlane` stopped at an interface (architecture.md §4: "envelope and HMAC input not documented precisely enough") no longer holds for the bar. The Quad integration reproduces BRAVIA Connect's frames byte for byte and the reflected schema confirms the layout:

1. **Seeds OAuth (PKCE S256).** `GET {auth}/user/authorize?response_type=code&scope=openid&client_id=…&redirect_uri=ssh-app://signin&code_challenge=…&code_challenge_method=S256`; `POST {auth}/token` (`grant_type=authorization_code`, then `refresh_token`). The `client_id` and an `x-api-key` header for the IoT host are BRAVIA Connect's own identifiers (public in that repo; **not** to be copied into tutti, see "Decisions for Erwann").
2. **Device list and keys.** `GET {iot}/devices` → device ids; `POST {iot}/devices/{device_id}/session_keys` → `device_id`, `key_id` (UUID), `session_key` (32 B hex), `hmac_key` (32 B hex), `expires_in` (~24 h). Refresh before expiry with the refresh token.
3. **Handshake per connection**, on `jp.co.sony.hes.ssh.controldevice.v1.ControlDeviceService`: `ConfirmSignin` (`auth_data = SHA-256(device_id)`; a `success=false` answer is tolerated) → `ConfirmKeys` (`key_data = HMAC-SHA256(hmac_key, key_id)`) → `GetNonce` → `GetSessionRandom` (→ `session_random` 8 B, `auth_token` 32 B).
4. **Signed requests.** `GetStatesWithAuthRequest { 1: serialized_request (bytes), 2: hmac (32 B) }`. The inner request is `{ 1: names { 1: path, repeated }, 2: session { 1: session_random (8 B), 3: key_id (UTF-8) } }`. **HMAC input = the inner serialized bytes only** (no outer tag/length, no field 2): `hmac = HMAC-SHA256(hmac_key, serialized_request)`. `ExecCommandWithAuth` uses the same rule over its own body: path, then one value wrapper (`4: {1: int64 varint}`, `5: {1: bool}`, `6: {1: string}`), plus the session embed; typically 68 bytes. Large varints are signed int64 (rear level −3 arrives as ≥ 2⁶³).
5. **Responses.** `GetStatesWithAuthResponse` wraps `{ serialized_response, hmac }`; values sit at field 2 → 1 → a sequence of length-prefixed (path, value) entries, walked in order (never by scanning for `0x0a`). Notify: `NotifyStatesWithAuth` → `{ serialized_states, hmac }`.
6. **Simplest working cadence** (@mafredri, confirmed by the Quad author 2026-07-05): `GetSessionRandom` before each RPC, then sign with `hmac_key`. BRAVIA Connect's rolling `auth_token` chain and its `client_control.mutex.any` preflight before writes are app habits, not requirements; keep the preflight only as a retry on `INVALID_ARGUMENT`. The device answers every mistake with a bare `INVALID_ARGUMENT`, so the encoder needs golden-byte tests, not trial and error.
7. **Keepalive.** The device closes channels that ping too often (`too_many_pings`); keepalive must be far above gRPC defaults.
8. **Notify-only paths.** On the Quad, `sound_setting.drc`, `speaker_sound_setting.360ssm_height`, `system_setting.earc`, `system_setting.dimmer`, `system_setting.hdmi_signal_format`, DSEE and auto standby accept writes but are **not readable** locally (absent from `GetStates`, `UNKNOWN` alone, never notified). BRAVIA Connect reads them from the cloud: `GET {iot}/devices/{id}/states`.
9. **`GetCapabilities` needs no auth** and returns `{"capabilities":[{"name":"<path>","type":"bool|int|enum|enum-array|any|void","props":{"min","max","values"}}]}`.

For the **TV**, the same service and auth work (the TV repo reuses the Quad machinery), with three differences: the port is dynamic (mDNS), the field schema is different (below), and some reads are nonce-gated and AES-256-GCM encrypted with `session_key` (the installed-app list). EU sets also gate picture writes behind an energy-label prerequisite (`*.prerequisites = [lot5Agreement]`, confirmed by writing `*.confirm_prerequisite`).

Transport on Android: the bodies are hand-encoded bytes anyway, so tutti needs no grpc-java. OkHttp with `Protocol.H2_PRIOR_KNOWLEDGE` speaks h2c; a gRPC call is a `POST /<service>/<Method>` with `content-type: application/grpc`, `te: trailers`, a 5-byte prefix (0, then big-endian length) per message, and `grpc-status` in the trailers. The notify stream is a streamed response body. One small `:app` transport, one `:protocol` codec with golden tests.

### Message shapes, field by field

Full method path: `/jp.co.sony.hes.ssh.controldevice.v1.ControlDeviceService/<Method>`. The handshake messages below come from the Quad integration's proto stubs, which work live for these calls; the three `*WithAuth` messages are **not** what those stubs say (an early, wrong reconstruction that always got `INVALID_ARGUMENT`), and follow the reflected layout instead.

| Method | Request | Response | Streaming |
| --- | --- | --- | --- |
| `ConfirmSignin` | `1 auth_data: bytes` = SHA-256(UTF-8 `device_id`) | `1 success: bool` (false is tolerated) | unary |
| `ConfirmKeys` | `1 session_id: string` (= `key_id`), `2 key_data: bytes` = HMAC-SHA256(`hmac_key`, UTF-8 `key_id`) | empty | unary |
| `GetNonce` | `1 session_id: string` | `1 nonce: bytes(8)`, `2: bytes(32)` | unary |
| `GetSessionRandom` | `1 session_id: string` | `1 session_random: bytes(8)`, `2 auth_token: bytes(32)`, `3 session_id` | unary |
| `GetCapabilities` | empty (per the integrations' use; not checked against reflection) | JSON capability list (above) | unary, **no auth** |
| `GetStatesWithAuth` | outer `{1 serialized_request: bytes, 2 hmac: bytes(32)}`; inner `{1 {1 path: string, repeated}, 2 {1 session_random: bytes(8), 3 key_id: string}}` | outer wrapper → `{serialized_response, hmac}`; values at 2 → 1 → repeated `(1 path, 2 value)` with value `{1 varint}` for int/bool/enum index or a string | unary |
| `ExecCommandWithAuth` | outer as above; inner = path, value wrapper (`4 {1 int64}`, `5 {1 bool}`, `6 {1 string}`), session embed | `1 success` (`08 01` = ok) | unary |
| `StartNotifyStates` | `1 session_id: string` | stream of `NotifyStatesWithAuth` → `{serialized_states, hmac}`, same (path, value) entries | **server stream** |

Keys: `hmac_key` is the 32-byte value (hex-decoded) from `POST /devices/{id}/session_keys`; `session_key` (32 B) is only used on the TV, as the AES-256-GCM key for nonce-gated reads. Algorithm everywhere: HMAC-SHA256. Response `hmac` fields can be checked the same way; the integrations do not, tutti should (a mismatch is a malformed reply, not a value).

Settings and readouts ride on the dotted paths in §1 and §2. The one Erwann pointed at: the bar's input card "TV · 2.0ch" is `playback_control.function` + `playback_control.audio_channel`, with `audio_format` and `sampling_rate` beside them. One more path from the proto worth noting: `system_setting.external_control` exists on gRPC too, so it joins the never-written list (guardrail 6).

### What remains uncertain on this plane

- **The OAuth client** (decision 3.1): only BRAVIA Connect's own `client_id`, `x-api-key` and `ssh-app://signin` redirect are known to work. Whether Seeds accepts another redirect (an `https` App Link or a tutti scheme) for that client is untested and probably not.
- The Bar 9 and first-generation BRAVIA 8 are untested by anyone in the sources: the HMAC rule is proven on the HT-A9M2 (bar) and the BRAVIA 8 II (TV).
- `GetCapabilities`' request shape; the `audio_channel` string format; refresh-token lifetime and Seeds rate limits.
- Whether a gRPC session from tutti and one from BRAVIA Connect coexist (the mutex suggests one writer at a time; reads probably coexist).

### Plan to make the row work

Read-only first: the row says "Connected" once tutti holds keys and a notify stream, and only readouts depend on it. Writes come later (phase-2 step 5).

| Step | Module | Work | Test | Cost |
| --- | --- | --- | --- | --- |
| a | `:protocol` `grpc/` | `GrpcFrame` (5-byte prefix codec), `CloudWire` encoders/decoders for every message above, signing (`HmacSha256` over the inner bytes), signed-varint decode, response `hmac` check | golden bytes built from the documented layouts (no captured device data, invented ids), round-trip and corrupt-input tests | M |
| b | `:protocol` `grpc/` | `SeedsAuth`: authorize URL builder (reuse `Pkce`), token/refresh/devices/session-keys request and response models over a `SeedsTransport` interface; `SessionKeys` already redacts | request shapes, expiry maths, refusal paths | S |
| c | `:protocol` `grpc/` | `CloudSession` state machine: handshake, `GetSessionRandom` before each RPC, re-handshake on `INVALID_ARGUMENT` once, reconnect on stream end, key refresh 10 min before expiry (`isExpired` exists) | fake transport scripted per step | M |
| d | `:app` | h2c transport on OkHttp (`H2_PRIOR_KNOWLEDGE`, private LAN only via `LanAddress`, keepalive pings off), mDNS `_sonysmarthome._tcp` for the TV's port | — (CI compiles) | S |
| e | `:app` | Custom Tab sign-in (no WebView, no password), redirect capture per decision 3.1, tokens and session keys in the Keystore-encrypted store only, never logged (`Redact`, headers out of logs), "Se déconnecter de Sony" wipes both | — | S |
| f | `:app` | bind a real `SonyCloudPlane` instead of `UnavailableSonyCloudPlane`; the row shows signed-out / connecting / connected / failed with a reason; failures never touch TCP or REST (unchanged rule) | — | S |
| g | `:app` | "Détails techniques" readouts from the notify stream (§1); `GetCapabilities` and handshake result in the diagnostic, values scrubbed | — | S |

Total for read-only Sony connection: **about 1.5 to 2 weeks** (L), most of it in a and c, where the device gives no error detail and golden tests are the only safety net. It needs Erwann's decision 3.1 before step e; steps a to d and g do not. Nothing in it needs his password, and nothing secret reaches the repo or the logs.

## 1. Technical readouts (Erwann's first ask)

| Readout | Device | Channel | Path / method | Auth | Notify or poll | tutti has | Cost |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Incoming audio format ("Dolby Atmos", "LPCM"…) | Bar | C | `playback_control.audio_format` (+ `audio_format_dual_mono`) | Seeds | notify | no | in the gRPC read build |
| Channel count ("2.0ch", "5.1.2ch") | Bar | C | `playback_control.audio_channel` | Seeds | notify | no | same |
| Sample rate | Bar | C | `playback_control.sampling_rate` | Seeds | notify | no | same |
| Upmixer / virtualizer in use, 360 Reality Audio | Bar | C | `playback_control.upmixer`, `.virtualizer`, `.is_360ra` | Seeds | notify | no | same |
| "No audio" and HDMI error | Bar | C | `playback_control.no_audio`, `.hdmi_error` | Seeds | notify | no | same |
| Audio dropouts | Bar | C | `notification.sound_dropout` | Seeds | notify | no | same |
| Bluetooth device, codec, signal | Bar | C | `playback_control.bt_device_name`, `bt_codec`, `bt_signal_strength` | Seeds | notify | no | same |
| Input ("TV") | Bar | A | `main.input` | none | notify | yes | — |
| Sound field on/off | Bar | A | `audio.soundfield` | none | notify | yes | — |
| Sound field **mode** (Dolby Speaker Virtualizer, Neural:X, 360SSM) | Bar | C | `sound_setting.sound_effect` (≠ TCP sound field) | Seeds | notify-only on Quad; cloud read | no | gRPC + Seeds read |
| ARC vs eARC link | Bar | A | `hdmi.audioreturnchannel` (`off`/`arc`/`earc`) | none | notify | yes (setting) | S: show it as a readout too |
| Internal temperature | Bar | A | `system.temperature` | none | poll | yes, in Technical details (unverified on the Bar 9) | — |
| Firmware, model | Bar | A / B | `system.version`, `system.modeltype`; B `system.version`, `system.modelname` | none | poll | yes (A) | — |
| Network type, internet reachable, Wi-Fi strength | Bar | B | `network.connectiontype`, `network.internet`, `wlan.strength` | none | poll | yes, in Technical details (unverified on the Bar 9) | — |
| Rear and sub link, versions, protection faults | Bar | C | `speaker_connection_setting.connection_status.{rl,rr,sw}`, `.version.*`, `error_status` | Seeds | notify | no | gRPC read |
| Video signal: resolution, frame rate, HDR format | TV | D | `playback_control.signal_info.video`, JSON `{quality, width, height, frame_rate, hdr_type}` (`hdr_type` seen: sdr, hdr10, hlg, dolby/dolbyvision) | Seeds | notify | no | gRPC read |
| TV audio signal: codec and channels, also when routed to the bar over eARC | TV | D | `playback_control.signal_info.audio`, JSON `{codec, channels}` (codecs seen: dolby_atmos*, dolby_truehd, dolby_digital(_plus), dts(_hd, _uhd), pcm/lpcm, aac, mpeg); empty while an app decodes, shown as PCM | Seeds | notify | no | gRPC read |
| Input | TV | E / D | `avContent.getPlayingContentInfo` (`source`, `title`, `uri`), `getCurrentExternalInputsStatus`; D `system_setting.input`, `system_setting.tvapp.input` | E: private (PSK) | poll / notify | yes (E) | — |
| Picture mode | TV | E / D | `video.getPictureQualitySettings` target `pictureMode`; D `display_setting.picture_mode` (60+ values incl. Dolby Vision variants) | E: none | poll / notify | yes (E) | — |
| Content mode, Theatre mode | TV | D | `display_and_sound_setting.content_mode`, `.theatre_mode` | Seeds | notify | no | gRPC read |
| TV audio output (speaker / HDMI / audio system) | TV | E / D | `audio.setSoundSettings` `outputTerminal` (write; read unverified), D `sound_setting.audio_output.active_devices` | E: generic | poll / notify | partial | — |
| HDMI-linked device (the bar, eARC) | TV | D | `hdmi_connected_device.sshinfo` | Seeds | notify | no | gRPC read |

**Is the bar's format/channels key reachable on TCP 33336 with no new auth? No.** Three independent vocabularies agree: Sony's own CIS-IP2 command set (sony-cisip2), every TCP key the Quad integration uses or probes, and the HTTP :54545 catalog list nothing for incoming format, channel count or sample rate. BRAVIA Connect's "TV · 2.0ch" is `playback_control.function` (`tv`) beside `playback_control.audio_channel` on the gRPC plane; the Quad integration shows exactly these as "TV / HDMI" attributes. The exact string format of `audio_channel` on the Bar 9 is unknown. The TV's video signal is likewise gRPC-only: Sony documents `getPlayingContentInfo` with `source`/`title`/`uri` and nothing about the signal.

**Cheapest path to the readouts.** There is no sign-in-free route to format, channels, resolution or HDR. The cheapest real path is a **read-only** gRPC build: Seeds sign-in, session keys, handshake, one `GetStatesWithAuth` of about fifteen paths per device, then `StartNotifyStates`. No writes, no mutex, no cloud state reads. Before writing a line of it, two zero-auth probes settle whether the paths exist on Erwann's hardware: `GetCapabilities` on the bar's 55051 and on the TV's mDNS port (once the h2c transport exists), and in the meantime the TCP and HTTP readouts that need nothing (temperature, Wi-Fi strength, network type, eARC mode) can ship on their own.

## 2. The full map

Risk levels: **R** read-only · **S** reversible setting · **D** dangerous (guardrails in §4) · **L** permanently list-only (guardrail 6 or 7). Cost: **XS** < ½ day, **S** ≤ 1 day, **M** 2 to 4 days, **L** 1 to 2 weeks, each counting only the increment on top of its plane.

### Bar, TCP 33336 (A), keys tutti does not use

From the Quad integration's `const.py`; none is in tutti's `TheatreFeature`. All need nothing new.

| Key | What | Risk | Proposal |
| --- | --- | --- | --- |
| `system.temperature` | internal temperature | R | **built**: diagnostic and Technical details readout |
| `system.timezone`, `system.language`, `system.destination` (region), `system.manufacturer`, `system.devicename` | identity and locale | R | **built**: diagnostic and Technical details; `devicename` shows on the phone but only as answered/refused in the report, and is blanked in the wire log |
| `tv.avsync` | AV sync on the TV path (distinct from `audio.avsync`) | S | **built read-only** (diagnostic, Technical details); a stepper once a report shows its range (S) |
| `network.mode`, `network.dhcp` | wired/wireless, DHCP on/off | R read, **L write** | **built read-only** (diagnostic, Technical details); `FeatureKind.ReadOnly`, so `set` drops them (guardrail 6) |
| `system.serialnumber`, `network.macaddress`, `network.ipaddress` | identifying | — | already `NEVER_ASKED`, unchanged |
| `system.externalcontrol` | the switch tutti's own socket depends on | **L** | already `NEVER_ASKED`; add to a never-written list (guardrail 6) |

**Diagnostic read-only `get` candidates (for the coordinator):** `system.temperature`, `system.timezone`, `system.language`, `system.destination`, `system.manufacturer`, `system.devicename`, `tv.avsync`, `network.mode`, `network.dhcp`. All read, none identifying beyond the device name (whose value the report already scrubs). Erwann's next report settles them with no sign-in.

### Bar, HTTP 54545 (B): no authentication, so treat with care

The request shape comes from the Theatre Quad integration, not Sony documentation, and remains optional on other models. On the HT-A9000, the real device has answered the read-only `http_get` probe with HTTP 500 while TCP 33336 remained fully healthy. tutti therefore treats an HTTP non-2xx or a 2xx body that is not `http_get_result` as **plane unavailable**, not as a soundbar failure. Connection failures still fail the diagnostic because they say something different about reachability.


| Key | What | Risk | Proposal |
| --- | --- | --- | --- |
| `network.connectiontype`, `network.internet`, `wlan.strength`, `airplay.accessoryname` | link health, AirPlay name | R | **built**: `BarHttpClient` (`theatre/BarHttp.kt`), one `http_get` from the `BarHttpKey` enum when Technical details opens, and a diagnostic step; AirPlay name blanked in the report |
| `system.version`, `system.modelname` | identity | R | not asked: TCP already reports model and firmware |
| `fw.check_update` | asks whether an update exists; the bar may call home | R? (side effect: a cloud check, maybe more) | **left out** until a capture shows it only checks (§6); on `BarHttpKey.NEVER_ASKED` meanwhile |
| `ssid.name`, `inet4.*`, `inet6.*`, `network.macaddress_*`, `system.publickey` | network identity | R but identifying | never asked, like `NEVER_ASKED` |
| `wlan.scan`, `wlan.esslist` | nearby Wi-Fi networks | R but privacy | never asked |
| `fw.upload`, `fw.request_update`, `fw.update` | firmware install | **L** (7) | no code path |
| `wlan.connect`, `wlan.connect_manual`, `wlan.reconnect`, `wps.*`, `network.applyconfig`, `inet4.conf_*`, `inet4.dhcp`, `inet6.enabled`, `inet6.dhcp` | network changes | **L** (6) | no code path |

### Bar, gRPC 55051 (C), beyond tutti's TCP surface

Paths from Connect's 177-path snapshot plus model extras; the Bar 9's own set is whatever its `GetCapabilities` returns.

| Path(s) | What | Risk | Readable locally | Notes |
| --- | --- | --- | --- | --- |
| readouts in §1 | format, channels, rate, BT, dropouts, speaker links | R | yes | the phase-2 headline |
| `power`, `volume`, `mute`, `playback_control.function` (+ `available_values`, `unavailable_reason`) | same as TCP, with reasons | S | yes (`mute` empty on the wire on Quad) | lets tutti say *why* an input is unavailable |
| `playback_control.playback_command` (`play`/`pause`/`next`/`prev`), `title`, `artist`, `album`, `jacket_url`, `position`, `duration`, `playback_state`, `service_name`, `spotify.*` | now playing and transport for Spotify, Bluetooth, AirPlay | S | yes | not in BRAVIA Connect's bar UI as transport keys |
| `sound_setting.sound_effect` | sound field mode (Dolby Speaker Virtualizer, Neural:X, 360SSM) | S | notify-only (cloud) | |
| `sound_setting.dts_dialog_control` (0 to ~6 dB), `voice_zoom` (+ `range`, `on_off`), `imax_enhanced`, `mix_stage`, `stereo_playback` (`up_mix`/`multi_stereo`), `sound_setting.av_sync.hdmi_auto`, `.av_sync.hdmi_0`, `.av_sync.arc` | sound tuning | S | mostly yes | several are model-specific |
| `sound_setting.drc`, `speaker_sound_setting.360ssm_height`, DSEE | | S | notify-only (cloud) | |
| `sound_setting.volume.subwoofer` (−10…10), `.volume.rear`, `.volume.bass`, `speaker_sound_setting.sw_phase` (0/180), `center_speaker_mode`, `center_speaker_distance` | speaker levels and phase | S | yes | subwoofer −10…10 is finer than TCP `main.bassstep` |
| `system_setting.dimmer` (`bright`/`dark`/`off`) | front display brightness | S | notify-only (cloud) | |
| `system_setting.hdmi_signal_format` (`standard`/`enhanced`/`enhanced_4k120_8k`) | HDMI pass-through bandwidth | **D**: a format the TV or source can't take blanks the picture | notify-only (cloud) | guardrails 1, 3, 8 |
| `system_setting.cec`, `cec_power_off_sync`, `hdmi_standby_through`, `nw_bt_standby`, `earc` | HDMI / standby | S (but `nw_bt_standby` off can stop the bar waking for tutti) | mixed | `nw_bt_standby` off: guardrails 1, 3, 8 |
| `system_setting.friendly_name`, `time_zone` | | S | yes | |
| `system_setting.ipv4_address`, `ipv6_address`, `wifi_mac_address_*`, `serial_number`, `ecia.device_id` | identifying | — | yes | never asked |
| `battery.life.rl`, `.rr` | rear battery (HT-A8-class) | R | yes | shown only if advertised |
| `speaker_sound_setting.testtone_playback` (+ `_focus`) | test tone through each speaker | **D**: loud, continuous | yes | guardrails 1, 3, 5, 8 |
| `playback_control.demosong_playback`, `.demosong_playback.360ra` | plays the demo song | S (loud) | yes | guardrail 1 |
| `playback_control.built_in_demo.mode` | retail demo mode | **D**: demo modes can loop and lock settings | yes | guardrails 1, 2, 3, 4, 8 |
| `sound_optimization.*` (`slm` sound level, `raee`, `sound_gps` speaker location; `measurement_status`, `measurement_result`, `is_measured`) | Sound Field Optimization / room measurement | R for results; **D** to start one (loud sweeps, overwrites the calibration) | yes | results are an easy readout; starting stays in Connect until proven |
| `speaker_connection_setting.manuallink_state`, `wireless_frequency_band`, `optional_speaker_link`, `speaker_sound_setting.easylocation.sw` | re-link rears/sub, change the wireless band | **D**: can drop the rears until re-paired by hand | yes | guardrails 1, 2, 3, 5, 8 |
| `bluetooth_setting.pairing_mode`, `connection_quality` | | S | yes | TCP already pairs |
| `fw_update.state`, `.progress`, `.target_version`, `.update_exist_spk`, `.error.*`, `.version.main` | firmware status | R | yes | readout |
| `fw_update.cancel_update` and any start | firmware | **L** (7) | | no code path |
| `easy_setup.*`, `system_setting.in_setup`, `setup_progress` | first-setup state | R | yes | read only |
| `ecia.agreement_info`, `ecia.pp_*` | Sony privacy-policy agreement | **L** (never written: it is a consent on Erwann's behalf) | yes | read at most |
| `client_control.mutex.any` | one-controller lock BRAVIA Connect takes before writes | R | yes | read it before a write to avoid fighting an open Connect session |
| `notification.settings_changed`, `.standby_power_status`, `.cast_service` | events | R | notify | refresh triggers |
| `speaker_sound_setting.position_tv.left_right`, `system_setting.video_output_for_pip_pbp_displayed_tv` | bar position relative to the TV; video output while the TV shows picture-in-picture | S | unknown | in the proto, absent from Connect's snapshot list: hidden candidates, `GetCapabilities` decides |
| `system_setting.external_control` | gRPC twin of TCP `system.externalcontrol` | **L** (6) | | never written |

### TV, gRPC (D), from a BRAVIA 8 II schema

| Path(s) | What | Risk | Notes |
| --- | --- | --- | --- |
| `playback_control.signal_info.video`, `.audio` | §1 readouts | R | |
| `display_setting.picture_mode` (+ `available_values`, `unavailable_reason`), `calibrated_picture_mode`, `brightness` (0…50) | picture | S | `lot5Agreement` prerequisite in the EU |
| `display_and_sound_setting.content_mode` (`video`/`game`/`pc`/`multiView`/`movie`), `.theatre_mode` (`filmmakerExperience`/`daytime`/`movieNight`/`quiet`/`dialogueFun`/`custom`/`off`, + `limitation_reason`) | Sony's "modes" | S | not in REST at all; the reasons explain greyed controls |
| `sound_setting.audio_output.active_devices`, `available_devices`, `activate_devices`, `deactivate_devices`, `activation_result` | choose TV speakers, the bar, Bluetooth, several at once | S (can silence the room) | richer than REST `outputTerminal` |
| `sound_setting.volume.{tv_speaker,hdmi,bluetooth,wired_headphone,rear,subwoofer}`, `voice_zoom`, `voice_zoom.tv_speaker` (−6…6), `bt_3d_surround` | per-output volumes and voice | S | rear/sub are TV-direct speakers, not the bar |
| `system_setting.application_list` (nonce-gated, AES-256-GCM with `session_key`), `system_setting.application`, `available_inputs`, `multiview` | apps and inputs | R | tutti already has apps via REST and v2 |
| `remote_key.shortcut` (`quick_settings`, `audio`, `guide`, `app_menu`, `menu`, `tv`), `remote_key.d_pad`, `skip_channels` | Sony shortcut menus | S | `audio` and `quick_settings` open the menus BRAVIA Connect deep-links to |
| `remote_start` (+ `unavailable_reason`: energy mode) | "Remote start" | read R; **off is L** (6): it is how tutti powers the TV on | |
| `system_setting.friendly_name` | | S | |
| `error_status`, `speaker_connection_setting.*`, `opspk.*` | TV-direct speakers | R / S | |
| `hdmi_connected_device.sshinfo` | linked Sony device | R | |
| `fw_update.update_exist`, `.info`, `.start_update_availability` | firmware status | R | |
| `fw_update.start_update` | firmware install | **L** (7) | no code path |
| `*.confirm_prerequisite = lot5Agreement` | EU energy-label consent before a picture write | S, but a consent | only on a direct tap that says what it agrees to |

### TV, REST (E): advertised in Sony's reference, unused by tutti

Whether the XR80 lists each one is exactly what `guide.getSupportedApiInfo` answers; the diagnostic already records it, so the next report settles this table.

| Method | Service | Risk | Value |
| --- | --- | --- | --- |
| `getSettings` / `setSettings` / `getSettingsSchema` | system (pro BZ, documented localhost-only) | S…D | **If the XR80 lists them, the biggest hidden item on the TV**: a schema-driven settings tree. Probe `getSettingsSchema` read-only first |
| `getSystemSupportedFunction`, `getCurrentTime`, `getRemoteDeviceSettings` | system | R | `getSystemSupportedFunction` **built** (diagnostic shape, Technical details with identifying values such as the `WOL` MAC hidden); the other two diagnostic (XS). `getRemoteDeviceSettings` shows "Control remotely"; its setter is **L** (6) |
| `getLEDIndicatorStatus` / `setLEDIndicatorStatus` | system | S | illumination LED |
| `getContentList`, `getContentCount`, `getSourceList`, `getSchemeList` | avContent | R | inputs with labels and icons, tuner channels |
| `getApplicationStatusList`, `getWebAppStatus`, `getTextForm` / `setTextForm` (needs `encryption.getPublicKey`) | appControl | R / S | on-screen keyboard state; tutti already types through v2 |
| `terminateApps` | appControl | S (kills apps) | guardrail 1 |
| `setAudioVolume` / `setAudioMute` with `target` (`speaker`, `headphone`) | audio | S | per-output volume without gRPC |
| `getScreenshot` | system | R, privacy | never offered |
| `getPublicKey`, `getSecretData` | encryption | — | only as a dependency of `setTextForm` |
| `requestReboot` | system | **D** | guardrails 1, 3, 5, 8 (it cuts every plane for a minute) |
| `setScreenRotation` | videoScreen | n/a on a TV | no |
| PIN registration `accessControl.actRegister` | accessControl | S | the PSK-free route for WoL MAC, already noted in architecture.md |

### Cloud-only (I)

`GET {iot}/devices/{id}/states` is BRAVIA Connect's only source for the bar's notify-only settings (DRC, DSEE, dimmer, sound field mode, 360SSM height, eARC, HDMI signal format) and returns them in one JSON. Device list, friendly names and session keys come from the cloud too. No setting was found that can only be **written** through the cloud: writes go to the device over gRPC. Anything Sony-account-side (device sharing, registration, privacy agreements) stays out of scope.

**Factory reset and service mode:** no key, path or method for either was found on any plane (A–I) in any source. Firmware install exists on B, C and D. Those remain list-only (§4).

## 3. Decisions for Erwann before phase 2

1. **Which OAuth client.** Seeds has no public developer program. Every open implementation signs in *as BRAVIA Connect*: its `client_id`, its `x-api-key`, its redirect `ssh-app://signin`. On Android that redirect is a scheme BRAVIA Connect itself registers, so a Custom Tab sign-in in tutti would either open BRAVIA Connect or show a chooser, unless tutti also claims `ssh-app` (a fight with Sony's app on the same phone). This is the real constraint behind phase 2, not the bytes. It keeps CLAUDE.md's rule (PKCE in a Custom Tab, password never seen), but it presents tutti to Sony as Sony's app, and Sony can revoke that at will. Those identifiers stay out of the repo either way (a build property or a value Erwann types once), so this is a product and ToS call, his. A fallback that avoids it entirely: none for format/HDR readouts.
2. **Cloud reads.** Notify-only bar settings are readable only from Sony's cloud. Opt-in, read-only, on a refresh tap or after a `no_audio` notify, never polled.
3. **Coexistence with an open BRAVIA Connect.** Read `client_control.mutex.any` before writes and back off if another controller holds it.

## 4. Guardrails per dangerous item

Numbers refer to the brief's list: (1) never automated; (2) developer mode, only if the device lists it; (3) read current value first, show it beside the new one, write disabled if the read fails; (4) typed confirmation; (5) dry run with the exact request, sent on a second confirmation; (6) never cut tutti's own link, held by a never-written list test extending `NEVER_ASKED`; (7) firmware, factory reset, service mode: list only, no code path; (8) log every dangerous write with before/after in the diagnostic log.

| Item | Plane | Guardrails | Stays list-only? |
| --- | --- | --- | --- |
| `system.externalcontrol` off, gRPC `system_setting.external_control` | A / C | 6 | **yes** |
| `network.mode`, `network.dhcp` writes | A | 6 | **yes** |
| `wlan.*`, `wps.*`, `network.applyconfig`, `inet4/6.*` writes | B | 6 | **yes** |
| TV `remote_start` off, "Control remotely" off (`getRemoteDeviceSettings` setter), IP control / PSK changes, `setWolMode` off | D / E | 6 | **yes** (tutti already writes WoL mode: restrict it to "on") |
| Bar `system_setting.nw_bt_standby` / TCP `system.netbtstandby` off | A / C | 1, 3, 8, plus a line saying tutti can no longer wake the bar | no (it is a legitimate power-saving choice; tutti already offers it) |
| Firmware install, cancel, upload (`fw.*` on B, `fw_update.start_update`, `cancel_update`) | B / C / D | 7 | **yes** |
| Factory reset, service mode | none found | 7 | **yes** |
| `ecia.*` agreements, `lot5Agreement` beyond a picture write the user asked for | C / D | 1, and a plain-words consent line | `ecia` yes; `lot5` only as part of the tapped write |
| `system_setting.hdmi_signal_format` | C | 1, 3, 8, plus an automatic revert prompt after 15 s without confirmation (the picture may be black) | no |
| Test tone, demo song | C | 1, 3 (tone), 5 (tone), 8 | no |
| `built_in_demo.mode` | C | 1, 2, 3, 4 ("DEMO"), 8 | no, but developer mode only |
| Rear/sub re-link, wireless band, easy location | C | 1, 2, 3, 5, 8 | no, developer mode only |
| Starting a room measurement | C | 1, 3, 5, 8 | no, but after phase 2's writes are proven |
| `requestReboot` | E | 1, 3 (power state), 5, 8 | no |
| `terminateApps` | E | 1 | no |
| Audio output switch that leaves no output active | D | 1, 3, 8 | no |

Implementation note for (6): today's `TheatreFeature.NEVER_ASKED` guards one plane. Phase 2 needs a single `NeverWritten` list per plane (TCP keys, HTTP 54545 keys, gRPC paths, REST methods/params) that every writer checks, and a test that walks each writer's encoder with every listed entry and fails if any produces bytes.

## 5. Recommended phase-2 order

| # | Step | Gives Erwann | Cost | Depends on |
| --- | --- | --- | --- | --- |
| 1 | Diagnostic probes, zero auth: the nine TCP `get` keys above; HTTP 54545 read-only identity (`system.version`, `system.modelname`, `network.connectiontype`, `network.internet`, `wlan.strength`); REST `getSystemSupportedFunction`, `getSettingsSchema` if listed, and the full `getSupportedApiInfo` list in the report | Facts from his own bar and TV for every "unverified" above | S | — |
| 2 | "Détails techniques" screen with what needs nothing: bar temperature, eARC/ARC, input, sound field, firmware, Wi-Fi strength, network; TV input, picture mode, output terminal, system info | First visible readouts, no sign-in | S | 1 |
| 3 | h2c gRPC transport (OkHttp prior knowledge) + `GetCapabilities` on the bar's 55051 and the TV's mDNS port, into the diagnostic | The exact path list of *his* devices; settles Bar 9 vs Quad and XR80 vs XR8M2 | M | — |
| 4 | Seeds sign-in and session keys (decision 3.1 first), handshake, signed `GetStatesWithAuth`, `StartNotifyStates`, read-only, golden-byte tests from the documented layout | Format, channels, sample rate, resolution, frame rate, HDR, TV audio codec, live, in the same screen | L | 3, Erwann's 3.1 |
| 5 | Signed `ExecCommandWithAuth` for reversible settings: TV content/theatre/picture modes and audio outputs; bar sound field mode, DTS dialogue, subwoofer −10…10, dimmer, now-playing transport | Most of what BRAVIA Connect does beyond TCP/REST | M | 4 |
| 6 | Opt-in cloud read of notify-only settings | DRC, dimmer, HDMI signal format, eARC, sound field mode shown with their real values | S | 4 |
| 7 | `NeverWritten` lists and test across planes, before any dangerous item | Guardrail 6 enforced | S | before 8 |
| 8 | Dangerous-but-allowed items behind their guardrails, developer mode where marked | Test tone, HDMI signal format, reboot, demo, re-link | M | 5, 7 |

Steps 1 and 2 can ship this week; 3 is independent of the sign-in question; 4 is the one that needs Erwann's decision and is the bulk of the work.

## 6. Still unknown

- Everything on the actual Bar 9 and XR80: which TCP keys answer, which gRPC paths `GetCapabilities` lists, whether REST `getSettingsSchema` exists there.
- The string format of `playback_control.audio_channel`, `audio_format` and `sampling_rate` on the Bar 9 (the Quad integration displays them raw).
- The `GetCapabilities` request message (empty, per the Quad integration's usage; not checked against reflection here).
- Whether Seeds accepts a non-BRAVIA-Connect user agent, its rate limits, the refresh token's lifetime.
- Whether the TV's gRPC listener needs the TV to be signed in through BRAVIA Connect first (the TV integration says it does; first-generation BRAVIA 8 untested).
- What BRAVIA Connect's UI hides: unanswerable without the APK (not reachable here; it would need blutter on `libapp.so` in any case).
- Whether `fw.check_update` on HTTP 54545 is side-effect free.

## 7. Handing off to BRAVIA Connect: deep links

Erwann's idea (2026-10-24): for a setting tutti can't do yet, a button "Ouvrir dans BRAVIA Connect", ideally on the right screen. Android's sandbox rules out reading Connect's data or session, so a launch is the only bridge.

**What is known about Connect's entry points.** The manifest itself was not read: the APK is still out of reach (Sources). What the sources establish without it:

| Entry | Intent data | Evidence | Lands on |
| --- | --- | --- | --- |
| Launcher | `ACTION_MAIN` + `CATEGORY_LAUNCHER`, package `jp.co.sony.hes.home` (resolved with `PackageManager.getLaunchIntentForPackage`) | Play listing; every installed launcher app | Connect's home screen |
| OAuth redirect | `ACTION_VIEW`, `BROWSABLE`, data `ssh-app://signin?code=…` (scheme `ssh-app`, host `signin`) | Seeds' authorize URL uses it as `redirect_uri`; on a phone the browser hands it to Connect, on a computer it fails or asks to launch an app (both integrations' READMEs) | Connect's sign-in completion: it expects a `code` matching a PKCE verifier Connect itself started. **Not a settings screen** |
| https App Links | none known | no `assetlinks.json` found (hosts unreachable here) | — |

So **no deep link to a settings screen is known**, and none is likely: Connect is a Flutter app, which usually exposes one Android activity and routes screens inside Dart; a path-level route (say `ssh-app://settings/sound`) would have to exist in `libapp.so`'s router, which a manifest would not show anyway. Firing `ssh-app://signin` without a pending sign-in is not a shortcut either: at best Connect ignores it, at worst it shows a sign-in error or disturbs its own session. tutti never sends it.

**The fallback is already shipped.** `openBraviaConnect()` (`app/src/main/java/io/kud/tutti/ui/common/External.kt`, from `bfcbfd8`) launches the launcher intent, falls back to `market://details?id=jp.co.sony.hes.home`, then to the Play web page. The manifest already declares the Android 11+ package visibility it needs:

```xml
<queries>
    <package android:name="jp.co.sony.hes.home" />
</queries>
```

`getLaunchIntentForPackage` returns null without it; `startActivity` on a `market://` or `https://` VIEW intent needs no `<queries>`. If a scheme deep link ever turns up, `resolveActivity` on it would also need `<queries><intent><action android:name="android.intent.action.VIEW" /><data android:scheme="…" /></intent></queries>`; a plain `startActivity` in a `try` does not.

**What a handoff can still do well, with no deep link:** say *what* to change. Each handoff row in §8 names the setting, and the button can name it too ("DSEE Ultimate : ouvrir BRAVIA Connect"). The menu path inside Connect is not documented in any source and must come from Erwann's phone, not from a guess.

**How to settle it for good, in two minutes, from Erwann's phone** (USB debugging, read-only, nothing installed):

- `adb shell dumpsys package jp.co.sony.hes.home`: the "Activity Resolver Table" lists every exported activity with its actions, categories, schemes, hosts and paths.
- `adb shell pm get-app-links jp.co.sony.hes.home` (Android 12+): any verified https domains.

If either shows a path-level filter, it goes in this table and `openBraviaConnect()` gains a target; if not, the launcher stays.

## 8. Coverage: the simple commands and the complicated ones

Erwann, 2026-10-24: "on veut absolument que tutti fasse autant les simples commandes que les compliquées." One row per BRAVIA Connect function and per hidden item (marked *hidden*: on a device plane or in the firmware's vocabulary, absent from Connect's UI). Rows describe neighbouring models unless §1–§2 say otherwise; `GetCapabilities` and the diagnostic settle the Bar 9 and XR80.

Channels: **ATV** Android TV Remote v2 · **REST** TV JSON-RPC (PSK where Sony's level is generic or private) · **TCP** bar 33336 · **HTTP** bar 54545 (no auth) · **gRPC** ControlDeviceService with Seeds keys · **cloud** Seeds `/states`.

Next step: **done** tutti does it · **now** buildable with what tutti already holds, no new setup · **PSK** a REST call behind the PSK Erwann sets once (tutti's IP-control step), no Sony account · **sign-in** needs the Seeds sign-in (decision 3.1) · **handoff** "Ouvrir dans BRAVIA Connect" until sign-in exists, for a reversible setting Erwann asked about by name or one that is Sony-account-side · **list-only** dangerous or cuts tutti's own link: listed, guarded per §4, never a button.

### TV, everyday

| Item | Channel | tutti today | Next | Note |
| --- | --- | --- | --- | --- |
| Power on / off | ATV, REST, WoL | yes | done | |
| Volume, mute | ATV | yes | done | |
| D-pad, back, home, media keys | ATV | yes | done | |
| Typing on the TV | ATV | yes | done | REST `setTextForm` not needed |
| Voice search | ATV | yes | done | |
| App list and launch | ATV, REST | yes | done | |
| Input switch | REST | yes | done | |
| What's playing (input, title) | REST | yes | done | |
| Sony shortcut menus (quick settings, sound menu) | gRPC `remote_key.shortcut` | no | sign-in | |
| Multi View / picture-in-picture inputs | gRPC `multiview` | no | sign-in | |
| Inputs with their labels and icons (*hidden*) | REST `getContentList`, `getSourceList` | no | PSK | |
| Tuner channels | REST `getContentList` (tv scheme) | no | PSK | low value without an aerial |

### TV, picture and sound

| Item | Channel | tutti today | Next | Note |
| --- | --- | --- | --- | --- |
| Picture mode | REST | yes | done | |
| Brightness, contrast, colour, sharpness, colour temperature | REST | yes | done | capability-gated |
| Light sensor, auto picture mode, HDR mode, colour space, local dimming, XDR | REST | yes | done | where listed |
| Scene select | REST `getSceneSetting` | yes | done | |
| Power saving, picture off | REST | yes | done | |
| Content mode (video, game, PC, movie), Theatre mode | gRPC `display_and_sound_setting.*` | no | sign-in | not in REST at all |
| Calibrated picture mode | gRPC | no | sign-in | |
| EU energy-label consent before a picture write | gRPC `confirm_prerequisite` | no | sign-in | only inside a tapped write |
| Sound output (TV speakers / audio system) | REST `outputTerminal` | yes | done | |
| TV speaker settings: position, subwoofer level, frequency, phase, power | REST `setSpeakerSettings` | yes | done | |
| Several outputs at once, per-device activation | gRPC `audio_output.*` | partial | sign-in | REST covers the single choice |
| Per-output volume (speaker, headphones) (*hidden*) | REST `setAudioVolume` with `target` | no | PSK | |
| TV Voice Zoom, Bluetooth 3D surround | gRPC | no | sign-in | |
| Acoustic Center Sync (TV as the centre speaker) | gRPC (bar `center_speaker_mode`, TV speaker) | no | handoff | Erwann named it |

### TV, system and hidden

| Item | Channel | tutti today | Next | Note |
| --- | --- | --- | --- | --- |
| Model, generation, system info | REST | yes | done | |
| Remote start (WoL) on | REST `setWolMode` | yes | done | restrict to "on" (§4) |
| Network settings readout | REST | yes | done | |
| Supported functions (*hidden*) | REST `getSystemSupportedFunction` | yes | done | Technical details, when IP control is up; identifying values hidden |
| Clock (*hidden*) | REST `getCurrentTime` | no | now | Sony level `none`; diagnostic |
| Settings schema tree (*hidden*) | REST `getSettingsSchema` / `getSettings` | no | PSK | biggest TV unknown; only if the XR80 lists it |
| Illumination LED (*hidden*) | REST `setLEDIndicatorStatus` | no | PSK | |
| Every path the TV advertises (*hidden*) | gRPC `GetCapabilities` | no | now | zero-auth, needs the h2c transport |
| Video signal: resolution, frame rate, HDR | gRPC `signal_info.video` | no | sign-in | |
| TV audio signal: codec, channels | gRPC `signal_info.audio` | no | sign-in | |
| Linked Sony device over HDMI | gRPC `hdmi_connected_device.sshinfo` | no | sign-in | |
| Firmware status | gRPC `fw_update.*` | no | sign-in | |
| Simple IP Control (*hidden*) | TCP 20060 | no | now | nothing A–G lacks; not worth a client |
| Restart the TV | REST `requestReboot` | no | list-only | |
| Close running apps | REST `terminateApps` | no | list-only | |
| Screenshot | REST `getScreenshot` | no | list-only | privacy |
| Remote start off, "Control remotely" off, PSK change, WoL off | REST / gRPC | no | list-only | cuts tutti's own link |
| Firmware install | gRPC `fw_update.start_update` | no | list-only | |

### Bar, everyday (TCP 33336)

| Item | Channel | tutti today | Next | Note |
| --- | --- | --- | --- | --- |
| Power, volume, mute | TCP | yes | done | |
| Input (TV, HDMI, Spotify, Bluetooth, AirPlay) | TCP | yes | done | |
| Bass / subwoofer step, rear level | TCP | yes | done | |
| Voice, sound field on/off, Night, 360 Spatial Sound Mapping | TCP | yes | done | |
| DRC, Auto Volume | TCP (polled) | yes | done | |
| IMAX Enhanced, dual mono, AV sync | TCP | yes | done | |
| Voice Zoom 3 and its level | TCP | yes | done | |
| HDMI: CEC, pass-through, standby link, ARC / eARC | TCP | yes | done | |
| Auto standby, network/Bluetooth standby, auto update | TCP | yes | done | standby off says tutti can't wake the bar |
| Model, firmware version | TCP | yes | done | |
| Bluetooth mode, connection quality, pairing | TCP | yes | done | |
| Internal temperature (*hidden*) | TCP `system.temperature` | yes | done | Technical details; unverified on the Bar 9 |
| Locale, region, device name (*hidden*) | TCP `system.*` | yes | done | name answered/refused only in the report |
| AV sync on the TV path (*hidden*) | TCP `tv.avsync` | yes | done | read-only; a stepper waits on a report |
| Wired/wireless, DHCP readout (*hidden*) | TCP `network.mode`, `network.dhcp` | yes | done | read only; writes list-only |
| Wi-Fi strength, network type, internet reachable, AirPlay name (*hidden*) | HTTP | yes | done | `BarHttpClient`, on opening Technical details |
| Firmware update available? (*hidden*) | HTTP `fw.check_update` | no | now | left out of build 1: not known to be check-only (§6) |

### Bar, what BRAVIA Connect adds

| Item | Channel | tutti today | Next | Note |
| --- | --- | --- | --- | --- |
| Sound field mode (Dolby Speaker Virtualizer, Neural:X, 360SSM) | gRPC write, cloud read | no | sign-in | |
| DSEE Ultimate | gRPC write, cloud read | no | handoff | Erwann named it |
| Height of Sound Field | gRPC `360ssm_height`, cloud read | no | handoff | Erwann named it |
| DTS Dialog Control | gRPC `dts_dialog_control` | no | handoff | Erwann named it |
| Sound Field Optimization: measure the room | gRPC `sound_optimization.*` | no | handoff | loud, overwrites calibration; stays in Connect until writes are proven |
| Sound Field Optimization: results | gRPC | no | sign-in | |
| Subwoofer −10…10, rear level, subwoofer phase, centre mode and distance | gRPC | partial | sign-in | TCP covers the coarser steps |
| Front display dimmer | gRPC write, cloud read | no | sign-in | |
| Mix stage, stereo up-mix vs multi-stereo | gRPC | no | sign-in | model-specific |
| AV sync per input (HDMI auto, HDMI, ARC) | gRPC | partial | sign-in | TCP has one AV sync |
| Now playing and transport (Spotify, Bluetooth, AirPlay) | gRPC | no | sign-in | |
| Why an input is unavailable | gRPC `unavailable_reason` | no | sign-in | |
| Rename the bar, time zone | gRPC | no | sign-in | |
| Incoming audio: format, channels, sample rate | gRPC `playback_control.*` | no | sign-in | "TV · 2.0ch" |
| Upmixer, virtualizer, 360 Reality Audio in use | gRPC | no | sign-in | |
| No audio, HDMI error, dropouts | gRPC | no | sign-in | |
| Bluetooth device, codec, signal | gRPC | no | sign-in | |
| Rear and sub link, versions, faults; rear battery | gRPC | no | sign-in | battery only if advertised |
| Firmware status and progress | gRPC | partial | sign-in | TCP has the version |
| Another controller holds the bar (*hidden*) | gRPC `client_control.mutex.any` | no | sign-in | read before writes |
| Bar position beside the TV, PiP video output (*hidden*) | gRPC | no | sign-in | only if `GetCapabilities` lists them |
| Every path the bar advertises (*hidden*) | gRPC `GetCapabilities` | no | now | zero-auth, needs the h2c transport |
| Notify-only values (DRC, DSEE, dimmer, eARC, HDMI format) | cloud | no | sign-in | opt-in, on a tap |
| First setup, Sony account registration, device sharing | cloud | no | handoff | Sony-account-side, out of scope for tutti |
| HDMI signal format (4K120 / 8K bandwidth) | gRPC | no | list-only | can blank the picture |
| Test tone | gRPC | no | list-only | loud, continuous |
| Demo song, built-in demo mode | gRPC | no | list-only | |
| Re-link rears and sub, wireless band, easy location | gRPC | no | list-only | can drop the rears |
| Firmware install, upload, cancel | HTTP, gRPC | no | list-only | |
| Wi-Fi, WPS, IP configuration changes | HTTP | no | list-only | cuts tutti's own link |
| External control off | TCP, gRPC | no | list-only | cuts tutti's own link |
| Sony privacy agreements (`ecia.*`) | gRPC | no | list-only | a consent on Erwann's behalf |
| Factory reset, service mode | none found | no | list-only | |

### Counts

| Next | Rows | of which *hidden* |
| --- | --- | --- |
| done | 35 | 6 |
| now | 5 | 5 |
| PSK | 5 | 4 |
| sign-in | 29 (4 of them partly done today) | 2 |
| handoff | 6 | 0 |
| list-only | 14 | 0 |
| **total** | **94** | **17** |

**Reading it.** The simple commands are done: every everyday TV and bar control BRAVIA Connect offers, tutti already does. What's left splits cleanly. The cheap, sign-in-free part is mostly *hidden* items (readouts, probes, a few REST setters). The complicated part BRAVIA Connect is known for (sound field mode, DSEE, DTS dialogue, height, fine levels, the signal readouts) sits almost entirely on gRPC, and gRPC writes need Seeds keys: there is no sign-in-free route for those, only the handoff.

### Next builds without Sony sign-in

1. **Zero-auth readouts into Technical details** (S). **Built 2026-10-24**, except `fw.check_update` (left out, §6). Refresh cost: the nine TCP keys are `TheatreFeature.detail`, asked only when the screen opens (refused ones skipped), never on connect or in the periodic re-ask. TCP `system.temperature`, `tv.avsync`, the locale keys and `network.mode`/`dhcp`; a read-only HTTP 54545 client for Wi-Fi strength, network type, internet and AirPlay name, plus `fw.check_update` on a tap; REST `getSystemSupportedFunction`. Closes every "now" row except `GetCapabilities`, and the diagnostic proves which keys the Bar 9 answers.
2. **h2c transport and `GetCapabilities` on both devices** (M). No keys, no writes. It turns every gRPC row from "neighbouring model" into fact for Erwann's bar and TV, and it is step d of the Sony-connection plan, so nothing is wasted whichever way 3.1 goes.
3. **The PSK rows** (S to M): per-output volume, labelled inputs, the LED, and a read-only probe of `getSettingsSchema`. If the XR80 lists the schema, that tree is the largest TV gain available without sign-in and gets its own plan.

Alongside, at XS: give each handoff row its own named button through the existing `openBraviaConnect()`, so DSEE Ultimate, Height of Sound Field, DTS Dialog Control, Acoustic Center Sync and room measurement each say what to change there. Dangerous rows stay list-only; none of these three builds writes to any of them.
