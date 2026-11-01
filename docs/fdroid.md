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
| NonFreeNet | No | All traffic is to the person's own TV and soundbar on the LAN (TCP 33336, TLS 6466 and the Cast port, REST, mDNS, Wake-on-LAN). The Sony cloud plane is an interface with `UnavailableSonyCloudPlane` only (docs/architecture.md, not pursued). The fdroid build has no GitHub updater. "Show on TV" sends YouTube or other links to the TV; the phone contacts none of those services. |
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

Blockers, in order:

1. **A final release.** F-Droid tracks final tags (`v0.2.0`, not `-dev.N`); none exists yet, and a final release waits for Erwann's word. The recipe drafts `v0.2.0` → versionCode `200099` by the CI formula.
2. **Version detection.** The versionCode lives in CI, not in the source, so F-Droid's checkupdates cannot read it from a tag; the recipe sets `AutoUpdateMode: None`, and each release needs a hand-added `Builds` entry. To automate it, the version would have to live in the source (a committed `version.properties`, CI tagging from it): a change to the release flow, to decide separately.
3. **AGP 9.5, Gradle 9.8.2, compileSdk 37.** Check that F-Droid's build server has them (unverified here; it fetches SDK platforms on demand).
4. **Screenshots.** `fastlane/metadata/android/*/images/phoneScreenshots/` is empty: there is no render harness on invented fixtures yet, and real screenshots would show real devices and apps. Add them from a fixture-driven render, never a phone capture.
5. **Categories.** Check `Connectivity` / `Multimedia` against fdroiddata's current category list.

Later, once a release key exists: reproducible builds with the developer's signature (`Binaries:` pointing at the GitHub asset and `AllowedAPKSigningKeys`), as pastille does. That needs the fdroid flavour's release APK attached under a name the updater never takes (`tutti-<version>-fdroid.apk`), and only after every install in use is new enough to skip it.

## Submitting (Erwann's to send)

1. Fork `fdroid/fdroiddata` on GitLab, copy `docs/fdroid/app.beansontoast.tutti.yml` to `metadata/app.beansontoast.tutti.yml`, with the final tag's versionName, versionCode and commit.
2. Locally: `fdroid readmeta`, `fdroid rewritemeta app.beansontoast.tutti`, `fdroid lint app.beansontoast.tutti`, then `fdroid build -v -l app.beansontoast.tutti`.
3. Open the merge request with the "App inclusion" template. The store text comes from `fastlane/metadata/android/` in this repo.
