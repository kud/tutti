# F-Droid

tutti ships as two flavours of the same app (`productFlavors` in `app/build.gradle.kts`):

- **`github`**: the APKs on GitHub Releases, with the in-app updater (`app/src/github`: `REQUEST_INSTALL_PACKAGES`, PackageInstaller, GitHub's release API). Every CI build and every release asset is this flavour.
- **`fdroid`**: no updater code, no install permission, no updater receiver. F-Droid builds it from source and updates it. About › Version says updates come through F-Droid.

Same `applicationId` (`app.beansontoast.tutti`) and versionCode formula in both. They are told apart by signature: an F-Droid install cannot update from a GitHub APK, nor the reverse, which is why the updater never takes an `*-fdroid.apk` asset and CI never attaches one (installs older than that rule would take it).

Guards: `checkDistribution<Variant>` reads each merged manifest before it assembles or lints (fdroid: no updater; github: the updater must be there); `DistributionGuardsTest` scans `src/main` and `src/fdroid` for any reach into the updater, with a probe proving it still matches `src/github`. The pure release-feed rules stay in `:protocol` `update/` (no network there); R8 drops them from an fdroid release since nothing references them.

## Anti-features

Assessed against the [F-Droid definitions](https://f-droid.org/docs/Anti-Features/):

| Anti-feature | Declared | Why |
| --- | --- | --- |
| NonFreeAssets | **Yes** | `res/drawable/brand_*` carry third-party logos: Simple Icons paths (CC0 data, trademarks not covered), svgl and Wikimedia Commons marks, and some apps' own Google Play icons (`docs/brand-icons.md`). The Play icons and press-kit marks are not freely licensed. To drop this, the fdroid flavour would fall back to the letter tile for every non-CC0 logo. |
| NonFreeNet | No | All traffic is to the person's own TV and soundbar on the LAN (TCP 33336, TLS 6466 and the Cast port, REST, mDNS, Wake-on-LAN). The Sony cloud plane is an interface with `UnavailableSonyCloudPlane` only (docs/architecture.md, not pursued). The fdroid build has no GitHub updater. "Show on TV" sends YouTube or other links to the TV; the phone contacts none of those services. The playback sheet's Share hands a sentence to the Android share sheet, and its optional Trakt and JustWatch buttons (off by default) open a search link on the phone when tapped: the browser or an app the person chose makes that request, tutti makes none, so this stays No (checked 2026-11-11). |
| NonFreeDep | No | No proprietary library (below), and no proprietary app is required on the phone. The Cast channel is tutti's own implementation of the protocol (`:protocol` `cast/`, Wire protos), not Google's Cast SDK. |
| NonFreeAdd | No, but a reviewer may ask | Setup and calibration hand off to Sony's BRAVIA Connect: tutti opens it, or its Play Store page when it isn't installed (`ui/common/External.kt`). That is a hand-off for tasks tutti can't do, not promotion. |
| Tracking, Ads, KnownVuln, UpstreamNonFree, NoSourceSince | No | No analytics, crash reporting or ads; the source is all here. |

## Dependencies

All free: AndroidX and Compose (Apache-2.0, Google Maven, which F-Droid accepts), Hilt/Dagger, Kotlin, kotlinx.coroutines and serialization, OkHttp, Wire (build-time codegen plus its Apache-2.0 runtime), BouncyCastle (MIT). No Google Play Services, Firebase or Cast SDK. Lint flags a trust-all `X509TrustManager` inside the BouncyCastle jar; tutti never uses it (devices are pinned, `PinningTrustManager`), and R8 removes it.

## Reproducibility

- No build timestamps or git calls in the build; versionName and versionCode come from the environment (CI) or from Gradle properties (F-Droid's `gradleprops`), else `0.0.0-dev` / 1.
- Toolchain pinned: `gradle/libs.versions.toml`, the wrapper with `distributionSha256Sum`.
- The only binaries in the tree are the Gradle wrapper jar and `app/debug.keystore` (public by design, used by debug builds only).
- `includeComposeMappingFile` is off, so no tool outside the build graph is needed.

## Before submitting

1. **The release.** F-Droid tracks final tags, never `-dev.N`. The recipe builds `v0.3.0` (commit `283e269`), versionCode `300099` by the CI formula (`(MAJOR × 1 000 000 + MINOR × 1 000 + PATCH) × 100 + 99`, `.github/workflows/android.yml`). It pins the full commit hash rather than the tag, so a moved tag can't change what F-Droid builds. The store changelog for it is `fastlane/metadata/android/*/changelogs/300099.txt`. (0.2.3 was prepared but never submitted.)
2. **Version detection.** The versionCode lives in CI, not in the source, so F-Droid's checkupdates cannot read it from a tag; the recipe sets `AutoUpdateMode: None`, and each release needs a hand-added `Builds` entry (and a `changelogs/<versionCode>.txt`). To automate it, the version would have to live in the source (a committed `version.properties`, CI tagging from it): a change to the release flow, to decide separately.
3. **AGP 9.5, Gradle 9.8.2, compileSdk 37.** Check that F-Droid's build server has them (unverified here; it fetches SDK platforms on demand). `fdroid build` below answers it.
4. **Screenshots.** `fastlane/metadata/android/{en-US,fr-FR}/images/phoneScreenshots/1.png`…`6.png` (remote, apps, sound, typing, scenes, Settings › Sync; English and French UI) are rendered at 1080×2424 (a Pixel 10) from invented fixtures by `TUTTI_SHOWCASE=<dir> ./gradlew --offline :app:testGithubDebugUnitTest --tests '*Showcase*'` (`app/src/test/.../showcase/ShowcaseRenders.kt`), never captured from a phone. To refresh them, run it and copy `<dir>/<en|fr>/<n>-<shot>-light.png` over the numbered files. F-Droid picks them up from this repo on the next build.
5. **Categories.** Check `Connectivity` / `Multimedia` against fdroiddata's current category list (`fdroid lint` flags an unknown one).

Later, once a release key exists: reproducible builds with the developer's signature (`Binaries:` pointing at the GitHub asset and `AllowedAPKSigningKeys`), as pastille does. That needs the fdroid flavour's release APK attached under a name the updater never takes (`tutti-<version>-fdroid.apk`), and only after every install in use is new enough to skip it.

## Submitting (Erwann's to send)

From your GitLab account, with Docker or a Debian/Ubuntu machine for `fdroidserver`:

1. **Fork** [`fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata) into your namespace, then clone the fork and branch:
   ```sh
   git clone git@gitlab.com:<you>/fdroiddata.git && cd fdroiddata
   git checkout -b app.beansontoast.tutti
   ```
2. **Add the recipe:** copy this repo's `docs/fdroid/app.beansontoast.tutti.yml` to `metadata/app.beansontoast.tutti.yml`. It already carries v0.3.0's versionName, versionCode and commit.
3. **Check and build it locally**, with fdroidserver (`pip install fdroidserver`, or the Docker image `registry.gitlab.com/fdroid/fdroidserver:buildserver`, which carries the Android SDK too):
   ```sh
   fdroid readmeta
   fdroid rewritemeta app.beansontoast.tutti   # normalises the file; keep what it changes
   fdroid lint app.beansontoast.tutti
   fdroid build -v -l app.beansontoast.tutti
   ```
   The build ends with an unsigned `app.beansontoast.tutti_300099.apk` under `tmp/`. A failure on AGP, Gradle or the SDK is point 3 above.
4. **Commit and push** to the fork: `git add metadata/app.beansontoast.tutti.yml && git commit -m "New app: tutti" && git push -u origin app.beansontoast.tutti`.
5. **Open the merge request** against `fdroid/fdroiddata` `master` with the "App inclusion" template. Tick its checklist (your app, MIT, no non-free dependencies) and point to the NonFreeAssets anti-feature and the BRAVIA Connect hand-off above, so the reviewer doesn't have to find them. The store text, changelog and screenshots come from `fastlane/metadata/android/` in this repo: nothing to copy.
6. **Answer the reviewer** in the merge request. Once merged, the app appears after F-Droid's next index build (usually a few days); then the F-Droid badge goes in the README.

Each later release: a new `Builds` entry (versionName, versionCode by the formula, full commit hash), `CurrentVersion` and `CurrentVersionCode` bumped, a `changelogs/<versionCode>.txt` in both languages here, and a merge request to fdroiddata with just that change.
