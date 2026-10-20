# salon

Native Android app that replaces Sony BRAVIA Connect day to day for one living room: a Sony BRAVIA 8 (K-xxXR80, Google TV, EU) and a BRAVIA Theatre Bar 9 (HT-A9000). The remote is the heart of it. Design direction: `docs/design.md`. Protocols and threat model: `docs/architecture.md`.

The name is provisional and lives only in `gradle.properties` (`app.name`, `app.label`, `app.applicationId`). Kotlin packages stay `io.kud.salon.*` whatever the product is called.

## Modules

- `:protocol`: pure Kotlin/JVM, no Android. Every wire format, state machine and reducer, with fast unit tests. Transports are interfaces (`net/Connection.kt`, `bravia/BraviaTransport.kt`) that `:app` implements.
  - `theatre/` soundbar TCP 33336, `atv/` Android TV Remote v2 (Wire protos in `src/main/proto/atv`), `bravia/` REST JSON-RPC, `grpc/` Sony-cloud plane (interface only), `scenes/`.
- `:app`: Android. Compose, Hilt (KSP), DataStore, Keystore-backed secrets, sockets/TLS, UI, Glance widget, Quick Settings tile, media notification.

Nothing else. A new concern goes into one of these two.

## Working here

- Cloud sessions cannot reach Google Maven or Maven Central: **you cannot compile**. CI is the compiler. Write real APIs at real versions, check imports by re-reading, and verify library versions with `git ls-remote --tags` on the upstream repo.
- Versions live in `gradle/libs.versions.toml`. AGP stays on 8.x and Hilt on 2.59.x until a deliberate move to AGP 9 + Gradle 9 + Hilt 2.60 (Dependabot ignores those majors).
- Tests: `./gradlew :protocol:test` for the protocol layer. New protocol behaviour comes with a test there.
- Never block the UI on the network: reducers are optimistic, the UI reads `StateFlow`s.
- Language: code, comments, commits and docs in English. The UI is French first: default `values/` is **French**, `values-en/` is English. Every string goes in both.

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
