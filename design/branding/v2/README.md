# tutti — Tactile brand exploration (v2)

> **One remote. The whole living room.**

![The proposed direction](review-board.svg)

![GitHub cover](github-cover.svg)

## Direction

This is the **first selected ivory-and-ink remote icon direction**: a softly sculpted dark navigation disc, a small top button and a single amber control. The mark must read immediately as a remote control. Do not return to the abstract double-t monogram.

The primary mark is subtly tactile, while the product interface remains calm and Material You-led. This is a proposal for Claude and the team to assess, **not an instruction to replace the installed launcher**.

## Palette and type

| Role | Colour |
| --- | --- |
| Ink | `#1C1B22` |
| Ivory | `#F4EFE7` |
| Amber | `#E5A14A` |
| Graphite | `#302E37` |
| Plum (example of dynamic UI colour) | `#625C70` |

Typography: **Inter Display** for brand headlines and **Inter** for UI; wordmark is provided as vector outlines, with no font files distributed.

## Assets

- [Review board](review-board.svg) — primary mark, dark/light/flat variants, palette, typography
- [Primary icon SVG](app-icon.svg) and [PNG 512](app-icon.png) — proposed launcher icon
- The selected image-generated reference is the art-direction source shown in the design discussion. The editable vector drawings deliberately simplify its highlights and are not exact raster copies.
- [Light icon](app-icon-light.svg), [flat icon](app-icon-flat.svg), [monochrome symbol](symbol-monochrome.svg)
- [Transparent symbol](symbol.svg), [dark lockup](lockup-dark.svg), [light lockup](lockup-light.svg)
- [GitHub cover](github-cover.svg)
- [Android foreground proposal](android/ic_launcher_foreground.xml) — candidate only, not installed

## Review notes for Claude

1. Compare these assets with the existing `design/branding/` proposal and choose whether to keep v2.
2. Check the icon in an Android home-screen grid at 48 px in both modes, respecting adaptive-icon masks.
3. Check the navigation disc remains legible at small sizes; the flat alternative exists for this purpose.
4. Check brand copy is **English by default**; translations belong to locale-specific app resources.
5. Inspect [review-board.svg](review-board.svg) and [github-cover.svg](github-cover.svg) on desktop and mobile for **text/image overlap or clipping**. Layout regions are separated in the source, but pixel-level rendering still needs visual verification.
6. After selecting the icon, align the splash-screen final frame to it exactly. Preserve the existing remote UI/D-pad; it is not in scope.

**Scope:** design files only. No Kotlin, Android resources in `app/`, package names, styles, release configuration or runtime behaviour change.

## Handoff

This PR includes production-usable source assets and a 512 × 512 PNG, but does **not** install them. Once the art direction is approved, Claude can copy the chosen source icon into the real Android adaptive-icon pipeline, export launcher densities from a single master and align the splash-screen rest state. Validate on-device before merging that follow-up. The current in-app D-pad is intentionally unchanged.
