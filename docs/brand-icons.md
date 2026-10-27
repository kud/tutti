# Brand icons

The app buttons (the app keys in More, the Apps sheet, the action picker and Dock pins that open an app) wear the app's real mark when the TV hasn't given us its own icon.

## Order

Every surface, in colour and full bleed (Erwann, 2026-10-25; `AppIcon`, rules in `BrandColours`, tested):

1. **The same app's icon on this phone**, through `PackageManager` (`PhoneIconBitmaps`). Matched by package only (`PhoneIcons`, protocol, tested): the TV package itself, then the catalog entry's verified phone package (`AppEntry.phonePackages`). Never by name. Visibility is `<queries>` for MAIN/LAUNCHER intents, never `QUERY_ALL_PACKAGES`; tutti asks only about the candidates and nothing leaves the phone. An adaptive icon is drawn full bleed, both layers cropped to the 72/108 safe zone; a legacy one fitted.
2. **The TV's launcher art** (`state.appIcons`), only ever from the TV itself (`AppIcons.plan`, `IconBook`). Square (aspect 0.8 to 1.25): cropped edge to edge. Wider: a banner, fitted to the tile's width on the colour its four corners share; when they disagree (more than 12 per channel, or transparent) it is skipped for the next source.
3. **The brand's official logo as a coloured tile** (`brandFace`): a Simple Icons mark on its brand hex, the glyph white or near-black by WCAG contrast (letter instead when neither reaches 3:1), at 56% of the tile; or a full-colour logo (svgl, Wikimedia Commons) in its own colours on its own ground, at 80% of the tile's longer side (Put.io's own square fills the tile); or, for Canal+, TF1+, M6+, Arte and Molotov, the app's own Play Store icon, cropped edge to edge like the phone's icon (`crop`).
4. **The app's first letter**, for apps outside the catalog with no phone or TV icon, and for catalog apps whose logo is not sourced yet (below).

The diagnostic's line for each app without a TV icon ends with what its tile shows instead (`shown: phone`, `logo` or `letter`), never which phone package matched.

## Rules

- Paths are copied unchanged from the pinned release by a one-off conversion (SVG `path` to VectorDrawable `pathData`, same 24x24 viewport). We scale and tint them; we never redraw, crop, recolour in parts or combine them.
- The marks remain the trademarks of their owners. tutti uses each only to identify the app a button opens, which is the use Simple Icons' disclaimer and the brands' guidelines allow. The Simple Icons data is CC0; that licence does not cover the trademarks.
- A logo comes from a recorded source (Simple Icons, svgl, a brand press kit, an official Wikimedia Commons file, the app's own Google Play icon), its paths or pixels copied unchanged; never drawn, traced or redrawn by us. A catalog app whose logo is not sourced yet keeps its letter tile until it is.
- To update: pick a new release tag (`git ls-remote --tags https://github.com/simple-icons/simple-icons`), fetch `icons/<slug>.svg` at that tag, copy its single `d` into `res/drawable/brand_<slug>.xml`, and update the table.

## Provenance

Release **16.35.0** (`https://raw.githubusercontent.com/simple-icons/simple-icons/16.35.0/icons/<slug>.svg`). Colour is Simple Icons' brand hex, recorded for reference; it is not used on screen.

| App | Slug | Drawable | Brand hex | Source (per Simple Icons) | Guidelines |
| --- | --- | --- | --- | --- | --- |
| Netflix | `netflix` | `brand_netflix` | `#E50914` | https://brand.netflix.com/en/assets/logos | https://brand.netflix.com/en/assets/logos |
| YouTube | `youtube` | `brand_youtube` | `#FF0000` | https://www.youtube.com/howyoutubeworks/resources/brand-resources/#logos-icons-and-colors | https://www.youtube.com/howyoutubeworks/resources/brand-resources/#logos-icons-and-colors |
| Apple TV | `appletv` | `brand_appletv` | `#000000` | https://en.wikipedia.org/wiki/File:Apple_TV_(logo).svg | none listed |
| HBO Max | `hbomax` | `brand_hbomax` | `#000000` | https://commons.wikimedia.org/wiki/File:Max_2025_logo.svg | none listed |
| Paramount+ | `paramountplus` | `brand_paramountplus` | `#0064FF` | https://www.paramount.com/brand/paramount-plus | none listed |
| MUBI | `mubi` | `brand_mubi` | `#000000` | https://mubi.com | none listed |
| Crunchyroll | `crunchyroll` | `brand_crunchyroll` | `#FF5E00` | https://www.crunchyroll.com | none listed |
| Twitch | `twitch` | `brand_twitch` | `#9146FF` | https://brand.twitch.tv | https://brand.twitch.tv |
| Spotify | `spotify` | `brand_spotify` | `#1ED760` | https://developer.spotify.com/documentation/general/design-and-branding/#using-our-logo | https://developer.spotify.com/documentation/general/design-and-branding/#using-our-logo |
| Deezer | `deezer` | `brand_deezer` | `#A238FF` | https://deezerbrand.com/document/37#/-/logo | https://deezerbrand.com/document/37#/-/logo |
| YouTube Music | `youtubemusic` | `brand_youtubemusic` | `#FF0000` | https://partnermarketinghub.withgoogle.com/#/brands | none listed |
| Jellyfin | `jellyfin` | `brand_jellyfin` | `#00A4DC` | https://jellyfin.org/docs/general/contributing/branding.html | https://jellyfin.org/docs/general/contributing/branding.html |
| Plex | `plex` | `brand_plex` | `#EBAF00` | https://brand.plex.tv | https://brand.plex.tv |
| Kodi | `kodi` | `brand_kodi` | `#17B2E7` | https://kodi.tv | none listed |
| VLC | `vlcmediaplayer` | `brand_vlcmediaplayer` | `#FF8800` | https://code.videolan.org/videolan/vlc/-/blob/1ce7f686ee17a028d2d79627ae69f22d905f2e23/extras/package/macosx/asset_sources/vlc_app_icon.svg | none listed |

### Added from 16.36.0 (2026-10-27)

Fetched with `npm pack simple-icons@16.36.0` from registry.npmjs.org. These apps are outside `AppCatalog`; `BrandMarks.kt` keys them by the TV package the diagnostic reported (`tvOnly`). On the BRAVIA the UK broadcasters are Sony's launcher wrappers (`com.sony.dtv.<name>launcher`), not the broadcasters' own packages.

| App | Slug | Drawable | Brand hex | TV package | Source (per Simple Icons) | Guidelines |
| --- | --- | --- | --- | --- | --- | --- |
| Channel 4 (was All 4) | `channel4` | `brand_channel4` | `#AAFF89` | `com.sony.dtv.all4launcher` | https://mediaassets.channel4.com/guidelines/guide/34286b7b-ea25-404d-a43b-e912fc85b0e0/page/8a2dd59a-51df-4f47-aa37-c235a761455e | https://mediaassets.channel4.com/guidelines/guide/34286b7b-ea25-404d-a43b-e912fc85b0e0 |
| ITVX | `itvx` | `brand_itvx` | `#DEEB52` | `com.sony.dtv.itvlauncher` | https://www.itvmedia.co.uk | none listed |
| LocalSend | `localsend` | `brand_localsend` | `#008080` | `org.localsend.localsend_app` | https://github.com/localsend/localsend/blob/f57cae55616e6d2f0ab20cad41e0697cba7885e5/app/assets/img/logo-512.png | none listed |

Not in Simple Icons 16.36.0, so still a letter until another recorded source has them: BBC iPlayer, BBC Sounds (no `bbc*` slug), Channel 5 / My5, YouView, Nuvio, My BRAVIA (Sony's wordmark is Sony's, not the app's). Play Games has no Simple Icons slug either and wears a symbol (below).

## Name fallback

A TV app whose package is unknown but whose title is exactly a catalog app's name (case-insensitive) wears that app's mark (`brandFace(packageName, name)`). Erwann's grid listed two "Plex": the catalog entry (`com.plexapp.android`, which the TV never listed, so `AppLibrary.all` adds it from the catalog with the phone's Plex icon) and the TV's own "Plex" under a package the catalog doesn't know. Both now wear Plex, and the Apps grid writes the package under any name that appears twice, so they read apart in words.

## System symbols

Sony's and Google's system apps have no free mark. Rather than a letter that says nothing, a few wear a filled Material symbol for what they are, on the letter tile's own ground and in its colour (`systemSymbol`, by package only; Iris, 2026-10-27). New symbols come from `@material-design-icons/svg` 0.14.13 (`filled/<name>.svg`, Apache 2.0).

| App | Package | Symbol |
| --- | --- | --- |
| BRAVIA notifications | `com.sony.dtv.notificationcenter` | `ic_notifications` (new) |
| Calman for BRAVIA | `com.sony.dtv.calibrationmonitor` | `ic_tune` |
| Help | `com.sony.dtv.smarthelp` | `ic_help` |
| Eco dashboard | `com.sony.dtv.ecodashboard` | `ic_bolt` |
| Browser (Vewd) | `com.vewd.core.browser` | `ic_language` (new) |
| File explorer | `com.cxinventor.file.explorer` | `ic_folder` (new) |
| Play Games | `com.google.android.play.games` | `ic_game` |

Left as a letter, deliberately: apps whose package no diagnostic has named yet (My BRAVIA, Music, Media Player), and Sony packages whose purpose is a guess (`livingfit`, `promos`, `smartmediaapp`, `osat.music.toast`). A symbol that names the wrong kind is worse than a letter.

## Not in Simple Icons

Prime Video, Disney+, Canal+, france.tv, TF1+, M6+, Arte, Molotov, Put.io. Simple Icons 16.35.0 carries none of them, and neither did its `develop` branch on 2026-10-23 (no `primevideo`, `amazonprime`, `disneyplus`, `canalplus`, `francetv`, `tf1`, `m6`, `arte`, `molotov` or `putio` icon, and no matching title in `data/simple-icons.json`). Once fetched over IP control, the TV's own icon covers them; until then the phone's icon does when the same app is installed on it, and otherwise the full-colour logo or Play Store icon below. Check again when picking a new release.

## Guidelines and single-colour rendering

Each mark is drawn in one colour, `onSurface`: near-black in light, near-white in dark. Black or white single-colour versions are the usual fallback brand guidelines allow when the brand colour can't be used, but the linked guidelines have not yet been read line by line (most are JavaScript pages that would not load for review). If one turns out to forbid a single-colour mark, draw that mark in its brand hex on the same tile and note it here.

## Phone packages

Checked on each app's Google Play page (`hl=en&gl=FR`) on 2026-10-25, by the devices it lists. Where the TV package also lists "Phone", it is the phone app too and needs no entry.

| App | TV package | Phone package | |
| --- | --- | --- | --- |
| Netflix | `com.netflix.ninja` | `com.netflix.mediaclient` | verified |
| YouTube | `com.google.android.youtube.tv` | `com.google.android.youtube` | verified |
| Prime Video | `com.amazon.amazonvideo.livingroom` (TV only) | `com.amazon.avod.thirdpartyclient` | verified |
| Spotify | `com.spotify.tv.android` (TV only) | `com.spotify.music` | verified |
| YouTube Music | `com.google.android.youtube.tvmusic` | `com.google.android.apps.youtube.music` | verified |
| Jellyfin | `org.jellyfin.androidtv` | `org.jellyfin.mobile` | verified |
| Disney+, Apple TV, Canal+, HBO Max, france.tv, TF1+, M6+, Arte, Molotov, Paramount+, MUBI, Crunchyroll, Twitch, Deezer, Plex, Kodi, VLC | same as TV | same as TV | TV package lists Phone |
| Put.io | `io.put.putio` (TV only) | none known | not verified: no phone package found, so only the TV package is asked |

## Full-colour logos (svgl)

From [svgl](https://github.com/pheralb/svgl) at commit `f9b726cee1d1f3c697a18c880185e89d9a55bb70` (fetched 2026-10-25, `https://raw.githubusercontent.com/pheralb/svgl/<commit>/static/library/<file>`). svgl's code is MIT; the logos are their owners' trademarks, used only to identify the app a button opens. Converted by a one-off script (paths and colours copied unchanged; a `rect` becomes the equivalent path; Disney+'s radial gradient keeps its stops, with its 4e-5 skew dropped from the transform).

| App | File | Drawable | Ground |
| --- | --- | --- | --- |
| Prime Video | `prime-video.svg` | `logo_primevideo` | white (the logo's colours are drawn for a light ground) |
| Disney+ | `disneyplus.svg` | `logo_disneyplus` | white (same) |
| Put.io | `putio.svg` | `logo_putio` | its own `#323232` square |

## Full-colour logos (Wikimedia Commons)

Taken only where the Commons file's own source is the brand's file, fetched 2026-10-25 from `https://upload.wikimedia.org/wikipedia/commons/<path>` and checked against the SHA-1 Commons records. Commons tags them public domain (below the threshold of originality); they remain their owners' trademarks, used only to identify the app a button opens. Converted by the same one-off script, extended for inherited group fill and opacity (`fillAlpha`) and for radial gradients written as `translate rotate(180) scale` (a unit circle, so the rotation changes nothing).

| App | Commons file | SHA-1 | Its source | Drawable | Ground |
| --- | --- | --- | --- | --- | --- |
| france.tv | `File:France.tv_-_logo_2022.svg` (`e/e0/…`) | `d977356a10eec0a933e1f5eaba2cbbcdecb8954d` | `https://www.france.tv/images/france-tv-black.svg` (France Télévisions) | `logo_francetv` | white (black wordmark) |

france.tv's fourth gradient (`paint3`) is an ellipse (scale 1260 x 2540) centred about 12,300 units off the drawing; VectorDrawable has no elliptical radial, so it is written as a circle of the larger radius. Every point of its path lies more than five radii out under either form, so both paint the last stop, `#FF5A5A`, and the result is identical. The grounds are tutti's neutrals, not brand colours: neither file carries a background.

## App icons (Google Play)

Where the logo alone didn't match the app (Erwann, 2026-10-25, on dev.95's Apps grid: "certains logos j'ai un doute, et aussi Arte n'est pas dans le bon sens"), the tile is the app's own icon from its Google Play listing: the listing page (`https://play.google.com/store/apps/details?id=<package>&hl=en&gl=FR`) names the icon on `play-lh.googleusercontent.com`, fetched at `=s512` on 2026-10-25 and copied byte for byte into `res/drawable-nodpi/` (512 px, enough for an 88 dp tile at xxxhdpi, 352 px). Each is its owner's trademark and copyrighted artwork, used only to identify the app a button opens. Drawn edge to edge and cropped like the phone's icon (`crop`), on its edge colour behind the rounded corners. To refresh: fetch the listing, take the first `play-lh` URL, append `=s512`, replace the file and its SHA-1 here.

| App | Package (listing) | Icon URL (`https://play-lh.googleusercontent.com/…=s512`) | SHA-1 | Drawable | Replaces |
| --- | --- | --- | --- | --- | --- |
| Canal+ | `com.canal.android.canal` | `HViN5bSGyYr2-aaq7eW5OnynnUmRYGSDE6CQdD_LYUbZjY-oINHgXXpuoC2PNP2h1wEiInS7J-eB78P_fRkE9g` | `b211c2e181637a7d1a3db1488d2dc64c67cedfaa` | `play_canalplus` | Commons reproduction of the 1995 wordmark; the app shows a white "+" on graphite |
| TF1+ | `fr.tf1.mytf1` | `dTGvET3BCy1lqjRnqAo6cwUBjzMaAK4zhWShUzwSbPW5Zbt_3PyrEz95eOsvC8UkGRQyg6ZhvnWkjn7gBWg8` | `d73834990c81ec26cd7efce709f99044f39d4da7` | `play_tf1plus` | Commons reproduction (navy on white); the app is white "TF1+" on blue |
| M6+ | `fr.m6.m6replay` | `CNdwh5jyns-M8NXEoNcPRpjWqDPWznaHzaikIzDnCNE63jq3ogA_IhJMYo9SYUPybjKDfmIIA3R4LsL21pTvjA` | `34cf44e49df5c548e4a342523660750cd611f26a` | `play_m6plus` | Commons reproduction citing a fan wiki; the shape was right (M6 with a yellow plus and bars), but black on white instead of the app's white on black |
| Arte | `tv.arte.plus7` | `v6o1XRbbbeSW7F_ZZEpPtcbPfrMunx0_-aAIxbFS8A3FS-RcyM-Ra7Q8vUMO1AQmsE_MeVjcWoqqwAjUZTzwrQ` | `b931e26582fe8e06ad3bfec5bf40d108967cbcb9` | `play_arte` | Commons 2017 wordmark, drawn vertically; the app reads "arte" left to right, white on orange |
| Molotov | `tv.molotov.app` | `zcyHmDgKNsa7_UzlDmxDJK1OoVgOp53rzClQbS7RYlUKcXNgGO_vZxmCGbKrH2iI1ODt3q0dA1v_HAvY9WDgcA` | `bbac07f07a7e8f9e12536cb5a6a6d05c793670e1` | `play_molotov` | Commons "molotov.tv by fubo" wordmark; the app is a black script "M" on amber |

Checked against the same day's Play icons and kept: Disney+ (svgl's wordmark is the one on the app icon, which sets it on a teal gradient), france.tv (France Télévisions' own wordmark; the app icon shows only its ".tv"), Put.io (svgl's file is the app icon; Erwann: "Le put.io est normal").

The Commons reproductions this replaces (`logo_canalplus`, `logo_tf1plus`, `logo_m6plus`, `logo_arte`, `logo_molotov`) were removed; their provenance is in git history before this change.
