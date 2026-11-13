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
| NonFreeNet | **Yes** (2026-11-12) | Optional features, each off by default and all switched off together by "Only free services" (Settings › Third-party services), reach or open non-free services: Title details sends the IMDb id of what plays to Stremio's Cinemeta (`v3-cinemeta.strem.io`), and Cast artwork fetches the picture the TV names from YouTube's, Google's, TMDB's or Stremio's image hosts. F-Droid defines NonFreeNet as an app that "promotes or depends entirely on a non-free network service" (https://f-droid.org/docs/Anti-Features/); tutti depends on none, and an opt-in arguably doesn't promote one, but reviewers apply the flag to optional features too, and declaring it costs less than a reviewer finding it. The feature stays in the fdroid flavour behind the same opt-ins (Erwann, 2026-11-12), and the master switch is the answer for anyone strict about free software. The rest of the app's traffic is to the person's own TV and soundbar on the LAN (TCP 33336, TLS 6466 and the Cast port, REST, mDNS, Wake-on-LAN). The Sony cloud plane is an interface with `UnavailableSonyCloudPlane` only (docs/architecture.md, not pursued). The fdroid build has no GitHub updater. "Show on TV" sends YouTube or other links to the TV; the phone contacts none of those services. The playback sheet's Share hands a sentence to the Android share sheet, and its optional Trakt, JustWatch and IMDb buttons (off by default) open a search link on the phone when tapped: the browser or an app the person chose makes that request, tutti makes none (checked 2026-11-11); they too are listed as non-free features and go off under "Only free services". |
| NonFreeDep | No | No proprietary library (below), and no proprietary app is required on the phone. The Cast channel is tutti's own implementation of the protocol (`:protocol` `cast/`, Wire protos), not Google's Cast SDK. |
| NonFreeAdd | No, but a reviewer may ask | Setup and calibration hand off to Sony's BRAVIA Connect: tutti opens it, or its Play Store page when it isn't installed (`ui/common/External.kt`). That is a hand-off for tasks tutti can't do, not promotion. |
| Tracking, Ads, KnownVuln, UpstreamNonFree, NoSourceSince | No | No analytics, crash reporting or ads; the source is all here. Developer mode's "Report on GitHub" (both flavours) only opens a prefilled `github.com/kud/tutti/issues/new` page in the browser, from a scrubbed in-memory event log; the person reads it and submits it with their own account, or not. The app makes no request, holds no token and sends nothing by itself, so neither rating changes. |

## Dependencies

All free: AndroidX and Compose (Apache-2.0, Google Maven, which F-Droid accepts), Hilt/Dagger, Kotlin, kotlinx.coroutines and serialization, OkHttp, Wire (build-time codegen plus its Apache-2.0 runtime), BouncyCastle (MIT). No Google Play Services, Firebase or Cast SDK. Lint flags a trust-all `X509TrustManager` inside the BouncyCastle jar; tutti never uses it (devices are pinned, `PinningTrustManager`), and R8 removes it.

## Reproducibility

- No build timestamps or git calls in the build; versionName and versionCode come from the environment (CI) or from Gradle properties (F-Droid's `gradleprops`), else `0.0.0-dev` / 1.
- Toolchain pinned: `gradle/libs.versions.toml`, the wrapper with `distributionSha256Sum`.
- The only binaries in the tree are the Gradle wrapper jar and `app/debug.keystore` (public by design, used by debug builds only).
- `includeComposeMappingFile` is off, so no tool outside the build graph is needed.

## Before submitting

Checked on 2026-11-13. Items 1 and 3 still block; the rest pass.

1. **The release (blocks).** F-Droid tracks final tags, never `-dev.N`. The recipe builds `v0.3.0` (commit `283e269`), versionCode `300099` by the CI formula (`(MAJOR × 1 000 000 + MINOR × 1 000 + PATCH) × 100 + 99`, `.github/workflows/android.yml`). It pins the full commit hash rather than the tag, so a moved tag can't change what F-Droid builds. (0.2.3 was prepared but never submitted.) v0.3.0 no longer matches what the submission says about it:
   - **The store listing comes from the built commit, not from `main`.** `fdroid update` reads `fastlane/metadata/android/` from the checkout of the last build (`build/<appid>/`). `283e269` predates `changelogs/300099.txt`, the Pixel 10 screenshots and `images/icon.png`, so a v0.3.0 listing would show no changelog and the 2026-11-03 screenshots.
   - **The NonFreeNet text describes `main`.** v0.3.0 has no Title details (Cinemeta), no Trakt, JustWatch or IMDb buttons and no "Only free services" switch; it fetches Cast artwork from `i.ytimg.com` and `lh3.googleusercontent.com` with no switch at all (`protocol/.../cast/CastArtwork.kt` at `283e269`).

   Either cut a final release from `main` and point the recipe at it (a new `Builds` entry with its full commit hash, versionCode by the formula, `CurrentVersion`/`CurrentVersionCode`, and `changelogs/<versionCode>.txt` in both languages), or keep v0.3.0, rewrite the NonFreeNet text for it, and accept the older listing until the next release.
2. **Version detection.** The versionCode lives in CI, not in the source, so F-Droid's checkupdates cannot read it from a tag; the recipe sets `AutoUpdateMode: None`, and each release needs a hand-added `Builds` entry (and a `changelogs/<versionCode>.txt`). To automate it, the version would have to live in the source (a committed `version.properties`, CI tagging from it): a change to the release flow, to decide separately.
3. **AGP 9.5, Gradle 9.8.2, compileSdk 37 (unverified, blocks).** Whether F-Droid's build server builds them is only answered by a real `fdroid build` (below), or by the `fdroid build` job of the merge request's own pipeline on GitLab.
4. **Store metadata.** `fastlane/metadata/android/{en-US,fr-FR}` has `title.txt` (6 characters, limit 50), `short_description.txt` (77, limit 80), `full_description.txt` (1 650 and 1 912, limit 4 000), `changelogs/300099.txt` (324 and 419 bytes, limit 500) and `images/phoneScreenshots/1.png`…`6.png`. `en-US/images/icon.png` is the launcher icon at 512×512: the adaptive icon's background and foreground layers (`res/drawable/ic_launcher_*.xml`), cropped to the 72 dp a launcher shows.
5. **Screenshots.** `phoneScreenshots/1.png`…`6.png` (remote, apps, sound, typing, scenes, Settings › Sync; English and French UI) are rendered at 1080×2424 (a Pixel 10) from invented fixtures by `TUTTI_SHOWCASE=<dir> ./gradlew --offline :app:testGithubDebugUnitTest --tests '*Showcase*'` (`app/src/test/.../showcase/ShowcaseRenders.kt`), never captured from a phone. To refresh them, run it and copy `<dir>/<en|fr>/<n>-<shot>-light.png` over the numbered files. The current set was rendered at `bcfd53a`, 17 commits after v0.3.0: showcase fixes plus one visible app change, the volume readout cut at the fill (`eb36d6e`). `main` has moved much further since, so render them again on the commit the recipe targets.
6. **Categories.** `Connectivity`, `Multimedia` and `Remote Controller` ("IR and smart home remotes for TVs, AC units, and other devices") are all in fdroiddata's `config/categories.yml`.
7. **Recipe.** `fdroid readmeta`, `fdroid rewritemeta app.beansontoast.tutti` and `fdroid lint app.beansontoast.tutti` pass inside fdroiddata's own `config/` (fdroidserver master, 2.4.2). The recipe is kept in the form `rewritemeta` writes, trailing spaces included, so the merge request's `rewritemeta` job finds nothing to change.

### Checking the build locally

The full check needs the build server image and a cold Gradle build of the fdroid flavour. Run it from the fdroiddata branch of "Submitting" below, with the recipe already copied in (`metadata/app.beansontoast.tutti.yml`):

```sh
docker run --rm -it --platform linux/amd64 -v "$PWD":/repo -w /repo \
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie bash -c '
    source /etc/profile.d/bsenv.sh
    apt-get update -qq && apt-get install -y -qq openjdk-21-jdk-headless
    command -v fdroid >/dev/null || {
      git clone -q --depth 1 https://gitlab.com/fdroid/fdroidserver.git /opt/fdroidserver
      export PATH=/opt/fdroidserver:$PATH PYTHONPATH=/opt/fdroidserver
    }
    export gpghome=none keystore=none keystorepass=none keypass=none serverwebconfig=none
    chmod 0600 config.yml
    fdroid build -v -l app.beansontoast.tutti'
```

Cost: the image is about 0.7 GB compressed (a few GB unpacked); the build then downloads the Gradle 9.8.2 distribution, AGP and every dependency (around 1 GB more) and the SDK platform for compileSdk 37 if the image lacks it. On an Intel Linux machine, expect 10–20 minutes; on an Apple Silicon Mac the amd64 image runs under emulation, so expect 30–60 minutes. Success ends with `unsigned/app.beansontoast.tutti_<versionCode>.apk`. The merge request's pipeline runs the same build on GitLab's runners, so skipping the local run only moves the wait there.

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
   The build ends with an unsigned `app.beansontoast.tutti_<versionCode>.apk` under `unsigned/`; "Checking the build locally" above runs it in Docker. A failure on AGP, Gradle or the SDK is point 3 above.
4. **Commit and push** to the fork: `git add metadata/app.beansontoast.tutti.yml && git commit -m "New app: tutti" && git push -u origin app.beansontoast.tutti`.
5. **Open the merge request** against `fdroid/fdroiddata` `master` with the "App inclusion" template. Tick its checklist (your app, MIT, no non-free dependencies) and point to the NonFreeAssets anti-feature and the BRAVIA Connect hand-off above, so the reviewer doesn't have to find them. The store text, changelog and screenshots come from `fastlane/metadata/android/` in this repo, as it stands at the commit the recipe builds: nothing to copy.
6. **Answer the reviewer** in the merge request. Once merged, the app appears after F-Droid's next index build (usually a few days); then the F-Droid badge goes in the README.

Each later release: a new `Builds` entry (versionName, versionCode by the formula, full commit hash), `CurrentVersion` and `CurrentVersionCode` bumped, a `changelogs/<versionCode>.txt` in both languages here, and a merge request to fdroiddata with just that change.
