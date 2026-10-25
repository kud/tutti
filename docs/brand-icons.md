# Brand icons

The app buttons (the app keys in More, the Apps sheet, the action picker and Dock pins that open an app) wear the app's real mark when the TV hasn't given us its own icon.

## Order

Every surface, in colour and full bleed (Erwann, 2026-10-25; `AppIcon`, rules in `BrandColours`, tested):

1. **The same app's icon on this phone**, through `PackageManager` (`PhoneIconBitmaps`). Matched by package only (`PhoneIcons`, protocol, tested): the TV package itself, then the catalog entry's verified phone package (`AppEntry.phonePackages`). Never by name. Visibility is `<queries>` for MAIN/LAUNCHER intents, never `QUERY_ALL_PACKAGES`; tutti asks only about the candidates and nothing leaves the phone. An adaptive icon is drawn full bleed, both layers cropped to the 72/108 safe zone; a legacy one fitted.
2. **The TV's launcher art** (`state.appIcons`), only ever from the TV itself (`AppIcons.plan`, `IconBook`). Square (aspect 0.8 to 1.25): cropped edge to edge. Wider: a banner, fitted to the tile's width on the colour its four corners share; when they disagree (more than 12 per channel, or transparent) it is skipped for the next source.
3. **The brand's official logo as a coloured tile** (`brandFace`): a Simple Icons mark on its brand hex, the glyph white or near-black by WCAG contrast (letter instead when neither reaches 3:1), at 56% of the tile; or a full-colour logo (svgl, Wikimedia Commons) in its own colours on its own ground, at 80% of the tile's longer side (Put.io's own square fills the tile).
4. **The app's first letter**, for apps outside the catalog with no phone or TV icon, and for catalog apps whose logo is not sourced yet (below).

The diagnostic's line for each app without a TV icon ends with what its tile shows instead (`shown: phone`, `logo` or `letter`), never which phone package matched.

## Rules

- Paths are copied unchanged from the pinned release by a one-off conversion (SVG `path` to VectorDrawable `pathData`, same 24x24 viewport). We scale and tint them; we never redraw, crop, recolour in parts or combine them.
- The marks remain the trademarks of their owners. tutti uses each only to identify the app a button opens, which is the use Simple Icons' disclaimer and the brands' guidelines allow. The Simple Icons data is CC0; that licence does not cover the trademarks.
- A logo comes from a recorded source (Simple Icons, svgl, a brand press kit, an official Wikimedia Commons file), its paths copied unchanged; never drawn, traced or redrawn by us. A catalog app whose logo is not sourced yet keeps its letter tile until it is. The one exception is the interim Commons reproductions below, accepted by Erwann until the brands' own files can be fetched.
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

## Not in Simple Icons

Prime Video, Disney+, Canal+, france.tv, TF1+, M6+, Arte, Molotov, Put.io. Simple Icons 16.35.0 carries none of them, and neither did its `develop` branch on 2026-10-23 (no `primevideo`, `amazonprime`, `disneyplus`, `canalplus`, `francetv`, `tf1`, `m6`, `arte`, `molotov` or `putio` icon, and no matching title in `data/simple-icons.json`). Once fetched over IP control, the TV's own icon covers them; until then the phone's icon does when the same app is installed on it, and otherwise the full-colour logo below (an interim Commons reproduction for Canal+, TF1+, M6+ and Arte). Check again when picking a new release.

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
| Molotov | `File:Logo_Molotov.tv.svg` (`2/2a/…`) | `b060115a9acd6b320d759fe8c6376b7da8aa3989` | molotov.tv | `logo_molotov` | near-black `#1C1B1F` (white and amber wordmark) |

france.tv's fourth gradient (`paint3`) is an ellipse (scale 1260 x 2540) centred about 12,300 units off the drawing; VectorDrawable has no elliptical radial, so it is written as a circle of the larger radius. Every point of its path lies more than five radii out under either form, so both paint the last stop, `#FF5A5A`, and the result is identical. The grounds are tutti's neutrals, not brand colours: neither file carries a background.

## Interim logos (Wikimedia Commons reproductions)

**Interim: Commons reproduction, not a brand-supplied file; replace with the brand's own SVG when its site is reachable (Erwann, 2026-10-25: "Je suis ok avec Wikimedia pour le moment").** This applies to every row of this table.

Fetched 2026-10-25 from `https://upload.wikimedia.org/wikipedia/commons/<path>`, requests paced against Wikimedia's 429s, each checked against the SHA-1 Commons records. Each remains its owner's trademark, used only to identify the app a button opens; the Commons licence covers the drawing, not the mark.

| App | Commons file | SHA-1 | Its stated source | Licence on Commons | Trademark | Drawable | Ground |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Canal+ | `File:Logo_Canal+_1995.svg` (`5/5d/…`) | `8808cfc5ef49879ae4fe1492d5ccb13f6a9a2659` | contributor reproduction: "own work based on" `File:Canal+.svg`, author given as Canal+ / Étienne Robial | public domain (below the threshold of originality) | Canal+ | `logo_canalplus` | black `#000000`, the logo's own box |
| TF1+ | `File:Logo_TF1+.svg` (`1/1f/…`) | `1b5f46360a55a00d9361e34e43d9047e8be7144d` | contributor reproduction: "Reproduction du logo de TF1+", own work by Llempereur - 4uatre | CC0 | TF1 | `logo_tf1plus` | white (navy wordmark) |
| M6+ | `File:M6+_(2024).svg` (`4/44/…`) | `fd1e8eadd1e653f930aa14edb5be414ff8444a10` | contributor reproduction, citing logos.fandom.com (a fan wiki); author given as Groupe M6 / Lonsdale Design | public domain | Groupe M6 | `logo_m6plus` | white (black M) |
| Arte | `File:Arte_Logo_2017.svg` (`4/43/…`) | `2ddcce2c9134abeb510e6c7b313780bdcef78614` | unknown source, derived from `File:Arte_Logo_2011.svg` | public domain | ARTE | `logo_arte` | near-black `#1C1B1F` (orange wordmark) |

- **Canal+, current mark.** Commons has no newer file for the Canal+ logo: the 1995 mark is still the current one, and `Logo_Canal+_1995.svg` is the file English Wikipedia's current Canal+ pages carry (Canal+ (French TV channel), Canal+ (streaming service), Canal+ S.A.). `Canal+_France_2011_logo.svg` is the former Canal+ France subsidiary's corporate mark, and the other `Canal+…` files are channel or country variants.
- **Arte stands upright.** The 2017 file draws the wordmark vertically (82.6 x 340 units), as Arte sets it on screen; it is kept as drawn, not rotated. A brand face now takes its share along the tile's longer side (`AppIcon`: `fillMaxSize(scale)`), so the wide logos are unchanged and Arte fills 80% of the height.
- **Conversion.** These files carry group and path transforms, which the earlier script refused, so a successor (`svg2vd3`, one-off, outside the repo) writes each `transform` as a VectorDrawable `<group>` (translate, or a matrix without skew as translate plus scale) and leaves every path's data unchanged. M6+'s gradients follow `xlink:href` for their stops. TF1+'s fill is a radial gradient in bounding-box units under a skewed matrix, which VectorDrawable cannot express; it is drawn exactly by clipping to the unchanged TF1+ path and painting a square in the gradient's own unit space, mapped by three groups (rotate, scale, rotate) from the matrix's SVD. Each drawable was written back to SVG and rendered against the original: identical for Canal+ and M6+ (largest channel difference 0 and 2 of 255), edge anti-aliasing only for Arte, and for TF1+ the gradient matches the SVG specification to within 1/255 in 99% of pixels (cairo's own render of the original is the one that strays).

### Still to replace

The brands' own sites (canalplus.com, groupe-canalplus.com, tf1.fr, tf1plus.fr, groupe-tf1.fr, m6.fr, m6plus.fr, 6play.fr, groupem6.fr, arte.tv) and Google Play's icon host (`play-lh.googleusercontent.com`) were refused by the session's network policy (checked 2026-10-25), so their header SVGs, manifests and app icons could not be read. Simple Icons, svgl, SVG Logos (gilbarbara), theSVG 3.4.2 and every Iconify set carry no official logo for them; Custom Brand Icons (CC BY-NC-SA, community traced) and Arcticons (redrawn line art) are not the official logos. When a brand's site is reachable, take its own SVG, replace the drawable and move the row to the official Commons or brand table.
