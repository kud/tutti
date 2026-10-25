# tutti

Native Android app that replaces Sony BRAVIA Connect day to day for one living room: a Sony BRAVIA 8 (K-xxXR80, Google TV, EU) and a BRAVIA Theatre Bar 9 (HT-A9000). The remote is the heart of it. Scope: the living room's remote for the BRAVIA, the Theatre Bar and the computer shown on the TV (Erwann, 2026-10-25: a Mac, Windows or Linux), never a general-purpose Bluetooth keyboard or trackpad (Erwann, 2026-10-25: « L'idée de tutti est d'être une remote control pour TV et Mac sur TV, pas un trackpad générique. »). Design direction: `docs/design.md`. Protocols and threat model: `docs/architecture.md`.

The name (tutti) lives only in `gradle.properties` (`app.name`, `app.label`, `app.applicationId`). Kotlin packages stay `io.kud.tutti.*` whatever the product is called.

## Modules

- `:protocol`: pure Kotlin/JVM, no Android. Every wire format, state machine and reducer, with fast unit tests. Transports are interfaces (`net/Connection.kt`, `bravia/BraviaTransport.kt`) that `:app` implements.
  - `theatre/` soundbar TCP 33336, `atv/` Android TV Remote v2 (Wire protos in `src/main/proto/atv`), `bravia/` REST JSON-RPC, `grpc/` Sony-cloud plane (interface only), `scenes/`.
- `:app`: Android. Compose, Hilt (KSP), DataStore, Keystore-backed secrets, sockets/TLS, UI, Glance widget, Quick Settings tile, media notification.

Nothing else. A new concern goes into one of these two.

## Working here

- Cloud sessions cannot reach Google Maven or Maven Central, but where `/root/android-sdk` and a warm Gradle cache exist **the whole build runs offline**: `export ANDROID_HOME=/root/android-sdk; ./gradlew --offline -q :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, then `grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml` must print 0. Run it before every push. Only a new or bumped dependency can't resolve offline; then CI is the compiler: write real APIs at real versions, check imports by re-reading, and verify library versions with `git ls-remote --tags` on the upstream repo.
- Versions live in `gradle/libs.versions.toml`. AGP stays on 8.x and Hilt on 2.58 until a deliberate move to AGP 9 + Gradle 9 + Hilt 2.59+ (the 2.59 Hilt plugin already needs AGP 9 APIs; Dependabot ignores those).
- Tests: `./gradlew :protocol:test` for the protocol layer. New protocol behaviour comes with a test there.
- Never block the UI on the network: reducers are optimistic, the UI reads `StateFlow`s.
- Language: code, comments, commits and docs in English. The UI is English by default: `values/` is **English**, `values-fr/` is French (Erwann's phone). Every string goes in both.

## Commits

`<gitmoji> <type>(<scope>): <lowercase imperative summary>`, closed mapping: feat ✨, fix 🐛, docs 📝, refactor ♻️, chore 🧹, revert ⏪️, test ✅, build 🏗️, ci 👷, perf ⚡️, style 🎨. Author `Erwann Mest <m@kud.io>`. No AI attribution (no `Co-Authored-By`, no session links).

## Never

- Commit a secret: the TV PSK, Sony tokens or session keys, pairing key material, the release keystore or its passwords.
- Log a secret. Use `Redact` and keep headers out of logs.
- Disable TLS verification or trust-all. Devices are pinned trust-on-first-use (`CertificatePin`).
- Send cleartext HTTP anywhere but a private LAN address (`LanAddress.isPrivate`).
- Use a WebView for Sony sign-in, or handle Erwann's password. OAuth PKCE in a Custom Tab only.
- Put real personal data (names, IPs, serials, device ids) in fixtures or tests.

## Secrets on device

Secrets live only in `files/datastore/secure.preferences_pb` and `files/secure/`, encrypted with an AndroidKeyStore key. Both paths are excluded from backup in `res/xml/data_extraction_rules.xml` and `backup_rules.xml`; keep those in sync if a path changes. "Oublier l'appareil" wipes pairing material; "Se déconnecter de Sony" wipes tokens and session keys.

## Releases

Pushing a `vMAJOR.MINOR.PATCH` tag builds a signed APK and attaches it to a GitHub Release. The four signing secrets are listed in the README. The release job fails rather than ship an unsigned APK.

A `-dev.N` pre-release needs no go-ahead (Erwann, 2026-10-25): once the full check above passes on a clean worktree, fast-forward `main` to the verified commit and run the Android workflow on `main` with the next dev tag (`vMAJOR.MINOR.PATCH-dev.N`; N stops at 98, so after `v0.1.0-dev.98` came `v0.2.0-dev.1`), then tell Erwann what it carries. Without the signing secrets it ships the debug APK. A final `vMAJOR.MINOR.PATCH` release still waits for his explicit word.

Maya and Iris decide within their craft without asking first (Erwann, 2026-10-25: « Je fais confiance à Maya et Iris à partir du moment que ça casse rien »): their recommendations ship as long as nothing breaks, meaning the full check passes, stored data and pairings survive (upgrade tests for any storage change), and no shipped behaviour regresses. Product scope, anything outward-facing, a final release, and reversing an explicit decision of his still go to him.
