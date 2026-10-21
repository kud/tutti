# tutti

An Android remote and control app for a Sony BRAVIA 8 TV and a BRAVIA Theatre Bar 9, built to replace BRAVIA Connect for everyday use. Google TV–grade remote (touchpad, D-pad, live keyboard mirrored from the TV), soundbar sound controls, scenes, shortcuts, a home-screen widget and a Quick Settings tile. It works on the local network without a Sony account; Sony sign-in is an opt-in extra.

Setup, room calibration and speaker placement stay in BRAVIA Connect.

## Set up the devices

1. **Soundbar.** In BRAVIA Connect, enable **External control** (IP control) for the Bar 9. The app talks to it on TCP port 33336.
2. **TV, remote.** Nothing to enable. On first launch the app pairs with the TV: the TV shows a 6-character code, type it in the app.
3. **TV, settings (picture, sound output, inputs).** On the TV: *Settings → Network & Internet → Remote device settings → Control remotely* on, *IP control → Authentication* set to **Normal and Pre-Shared Key**, and choose a Pre-Shared Key. Enter the same key in the app.
4. Give the TV and the bar fixed IP addresses on your router (recommended).

## Install

Download the latest `tutti-<version>.apk` from [Releases](../../releases) on the phone and open it (allow installs from your browser or file manager once). Later releases install over it.

## Build

```sh
./gradlew :protocol:test :app:assembleDebug
```

JDK 17. CI (`.github/workflows/android.yml`) runs tests, lint and a debug build on every push and pull request.

## Release secrets

A `vMAJOR.MINOR.PATCH` tag builds a signed release APK and publishes it on a GitHub Release. The repository needs these Actions secrets (*Settings → Secrets and variables → Actions*):

| Secret | Content |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The release keystore, base64-encoded (`base64 -i release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | Key password |

Use one keystore forever: Android only installs an update signed with the same key. Create it once, for example:

```sh
keytool -genkeypair -v -keystore release.jks -alias tutti -keyalg RSA -keysize 4096 -validity 36500
```

Keep it (and its passwords) somewhere safe outside the repo. If a secret is missing, the release job fails and nothing is published.

## Docs

- `docs/design.md`: design direction.
- `docs/architecture.md`: modules, protocols (verified and not), threat model.

## Credits

The Android TV Remote v2 protos and pairing hash come from [kud/androidtv-remote](https://github.com/kud/androidtv-remote) (MIT, © 2021 louis49, © 2026 Erwann Mest). Soundbar protocol knowledge from [bravia-quad-homeassistant](https://github.com/steamEngineer/bravia-quad-homeassistant) (MIT), reimplemented.
