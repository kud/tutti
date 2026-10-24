# Brand icons

The app buttons (the app keys in More, the Apps sheet, the action picker and Dock pins that open an app) wear the app's real mark when the TV hasn't given us its own icon.

## Order

1. **The TV's launcher art**, once fetched over IP control (`state.appIcons`). It is the app's own icon, in its own colours. Only ever from the TV itself (`AppIcons.plan`): a blank icon URL counts as none; one naming another address of the TV (loopback, a second interface, a LAN name) is aimed back at the configured, pinned host; an internet host is never fetched. An HTTP icon on a pinned TV with port 80 shut is asked over HTTPS at the same path, a 401/403 once more with the PSK, and anything that is not an image is not cached. A failed icon is asked again on later polls, 30 s, 2 min, 10 min, 30 min, then hourly (`IconBook`); the diagnostic lists each app still without one and why.
2. **The brand's official mark** from [Simple Icons](https://github.com/simple-icons/simple-icons), for the apps in `AppCatalog` it covers.
3. **The app's first letter**, for everything else.

All three sit on the same `surfaceContainerHighest` tile. The mark is drawn monochrome in `onSurface` (near-black in light, near-white in dark) at 60% of the tile: it reads by shape, never by colour, holds contrast on any dynamic palette, and keeps a row of mixed sources calm. The button around it always carries the app's name as text.

## Rules

- Paths are copied unchanged from the pinned release by a one-off conversion (SVG `path` to VectorDrawable `pathData`, same 24x24 viewport). We scale and tint them; we never redraw, crop, recolour in parts or combine them.
- The marks remain the trademarks of their owners. Tutti uses each only to identify the app a button opens, which is the use Simple Icons' disclaimer and the brands' guidelines allow. The Simple Icons data is CC0; that licence does not cover the trademarks.
- A brand Simple Icons does not carry keeps its letter tile. Never draw, trace or scrape a logo to fill the gap.
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

## Not in Simple Icons (letter tile)

Prime Video, Disney+, Canal+, france.tv, TF1+, M6+, Arte, Molotov, Put.io. Simple Icons 16.35.0 carries none of them, and neither did its `develop` branch on 2026-10-23 (no `primevideo`, `amazonprime`, `disneyplus`, `canalplus`, `francetv`, `tf1`, `m6`, `arte`, `molotov` or `putio` icon, and no matching title in `data/simple-icons.json`). Once fetched over IP control, the TV's own icon covers them; until then they keep the letter tile, Prime Video's in the All keys sheet included. Check again when picking a new release.

## Guidelines and single-colour rendering

Each mark is drawn in one colour, `onSurface`: near-black in light, near-white in dark. Black or white single-colour versions are the usual fallback brand guidelines allow when the brand colour can't be used, but the linked guidelines have not yet been read line by line (most are JavaScript pages that would not load for review). If one turns out to forbid a single-colour mark, draw that mark in its brand hex on the same tile and note it here.
