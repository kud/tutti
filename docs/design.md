# Design direction (Iris)

The app replaces BRAVIA Connect day to day, with a Google TV–grade remote at its heart. Setup and room calibration stay in BRAVIA Connect.

## Information architecture

Three places, opening on the remote.

1. **Remote (home)**
   - Top: one system chip, "Salon · BRAVIA 8 + Bar 9", single status dot; it splits into two lines only when one device drops ("Bar 9 injoignable"). One Power button switches both.
   - Below: a horizontal strip of scene pills.
   - Under the pills, in the room the pad leaves: the now-showing card (Iris's option A, chosen by Erwann, 2026-10-24), centred in that room so the band reads as meant, never as a void. Full width less 16 dp margins: the app's icon (or the state's glyph), "On the TV", the name large and a small detail line. It says an app, a channel and programme, or an HDMI input (`nowShowing`), else "TV on standby" (with "The bar stays on" when the bar plays), the home screen, or "TV out of reach"; never empty, and every state is told by its icon and words, never colour alone. Full is 104 dp tall and grows to 140 dp (larger icon and name) when the room allows, with at least 12 dp above and below; below that room it shrinks to one 56 dp line, and below that it hides (`placePad`, `cardFor`). While it shows, the system chip drops its "On the TV" line; hidden, the chip gets it back. A tap does nothing for now. State changes cross-fade by opacity only, which is also the reduced-motion form.
   - The pad mode switch (K1, Erwann, 2026-10-24): one quiet glyph on the pad's surface, in its top-right corner, that leaves the pad's shape alone. It shows the mode a tap switches to, as that mode's outline icon (the touchpad's in D-pad mode, the D-pad's in touchpad mode), 24 dp, on-surface-variant, 16 dp in from the top and right edges, with no chip, border or label; the pad already says the current mode. A 48 dp hit area round it; pressed, the glyph tints and tightens. A still tap there switches with a select haptic and never reaches the TV as OK; a touch that starts there and moves is an ordinary swipe, and a swipe crossing it tracks as normal. In D-pad mode the disc shrinks a little on a near-square pad so its reach never touches the corner (`dpadFaceRadius`, tested). The pad morphs by a spring, or a fade when motion is reduced. One button to TalkBack: the state is the current mode, the action "Switch to touchpad" / "Switch to D-pad", with an action for each mode.
   - Low, within the thumb's reach (Erwann, 2026-10-24: the thumb reaches about half the screen): the pad, the largest element, with the volume rail on its right at exactly its height, both standing 24 dp above the button row. Free room goes above the pad, as the now-showing card's room, never below the pad, and the pad never grows taller than its width (Compact 0.85, Large 1.2), so a tall screen doesn't stretch it upward (`placePad`). Touchpad or D-pad. Tap = OK. Long-press Select is reserved for the TV (sends a long press).
   - Under the pad: Back, Home, Play/Pause, Mic, Keyboard, spread evenly. Mute is the volume rail's foot: the speaker there is a 48 dp key on a faint disc, the icon at full strength; a tap toggles mute, muted shows the slashed speaker over the hatched fill, and with the bar unreachable the rail is a hollow track and the speaker can't be tapped. The rail's drag starts only past touch slop, so a tap never moves the level, and a drag that starts on the speaker cancels the tap. The mic is hold to talk to the TV's assistant: it grows and fills with the primary colour while listening, a pill above it reads "À l'écoute… relâchez pour envoyer"; sliding a finger off turns it to the error container ("Relâchez pour annuler"), with a tick at the edge. A tap shorter than 300 ms sends nothing and explains the gesture. (Maya's placement, pending Iris's review.)
   - Right of the pad: vertical volume rail (soundbar volume, absolute), the pad's height, top and foot level with it.
   - Bottom: a dock of 4 shortcut slots.
   - Peek sheet "Son" at the bottom; pulled up it shows sound controls.
2. **Scenes**: gallery and editor.
3. **Réglages**: devices and pairing, TV settings (picture, sound output, inputs, power saving), soundbar system settings, app preferences, haptics.

One tap from the remote: power, volume, mute, input, keyboard, any scene, any shortcut, Night, Voice.
Two levels deep at most.

### "Son" sheet
Sound field, Voice, Night, DRC, Auto volume, IMAX, 360 Spatial as large toggles; Bass and Rear level as sliders; Voice Zoom 3 as a level slider only if the bar answers the capability probe. A greyed setting always shows its reason ("Voix désactivée : virtualiseur actif"), never hidden. "Recalibrer dans BRAVIA Connect" deep link.

### Sheets
Every sheet (Son, All keys, Apps, a pin's editor, the action picker) can rise to just below the status bar and no further: its handle and title always show, and what doesn't fit scrolls inside the sheet. Dragging down from the top of that scroll moves the sheet. Modal sheets go through `TuttiSheet` (`ui/common/Sheets.kt`).

### Shortcuts
- Dock: four pins a page, up to three pages (swipe; dots only from page 2). Each pin has a name, an icon or two letters, a tap action (one action, or ordered steps with waits, tried from the editor with "Try") and its own long-press action, offered first in a menu above "Edit this pin".
- Long-press on any dock slot or secondary button (Home, Back, Play) assigns a second action.
- Pad gestures, two only: two-finger tap = play/pause; two-finger vertical drag = bar volume.

### Edit mode
- Settings › Shortcuts › "Remote layout", or "Edit the remote layout" at the foot of a pin's editor, turns the remote into its own edit mode: the same screen, what can change in a dashed frame, what can't under a lock. Changes save as they are made; "Done" (or Back) leaves.
- Adjustable: the round-button row (up to six, from Back, Home, Play/Pause, Voice, Keyboard, Info, Subtitles, Input, Guide, TV settings; drag sideways or TalkBack's move left / right), the scene strip (shown or hidden), the pad in three steps (Compact is shorter, Normal is as tall as it is wide, Large takes the margins back and stands taller; the rail follows the pad's height in each), and the pins' order within a page (drag, or move left / right; tap opens the pin's editor).
- Anchors never move or hide: Power, the volume rail, the pad's place and the Son sheet. "Reset the layout" asks first and puts the row, the scenes and the pad back; pins stay.
- Stored with every field defaulted to today's screen, so a phone that never opens edit mode sees no change.

## Scenes (templates)
- A scene holds any subset of TV settings (input or app, picture mode), bar settings (sound field, voice, night, bass, volume, optional volume cap) and a final action (launch an app).
- Main creation path: "Capturer l'état actuel" — every captured value becomes a removable chip; name, colour and glyph. Blank form is the fallback.
- Per-app profiles: "Quand Netflix est au premier plan, appliquer Cinéma" (while connected).
- Strip: pill in its own tonal colour. Gallery: large card with plain-words summary ("Atmos · Voix + · Basses −2"). Active scene filled; manual change → outline marked "modifiée". Applying fills the pill left to right (progress).
- Starter gallery: Cinéma, Série du soir, Nuit (volume cap 20), Musique, Sport, Jeu, Info.

## Keyboard
- Auto-open when the TV reports a focused text field (IME show request); the pad shrinks above, still live.
- Mirror field at the top showing the TV's current text and label ("Rechercher sur YouTube").
- Each keystroke sent live; Enter carries the TV's action. Chips: Coller, Dicter. Closes when the TV field loses focus or on swipe down.
- Hardware keyboard: arrows → D-pad, Enter → OK, Esc → Back, text → IME.

## Quick access (v1)
Quick Settings tile (opens a compact remote overlay), phone volume buttons drive the bar while the app is in front, home-screen widget (scenes + volume), media-style notification with volume, mute and active scene.

## Visual
- Material 3 Expressive, restrained: dynamic colour, springy shape changes, big rounded targets. Expressive in motion, not decoration.
- Dark first: near-black surface, optional true black for OLED, no white flashes. Light theme secondary.
- Pad: one large quiet surface, no grid lines. D-pad mode is a tonal disc with a chevron at each point and an OK centre set off by a thin moat; the drawn zones are the hit zones, and the one under the finger lights until release. Volume second loudest. Everything else tonal, not outlined.
- Haptics are primary feedback: light tick per D-pad/swipe step, firmer click on Select, soft bump at volume limits/cap, double pulse when a scene finishes.
- Motion: springs under ~250 ms; reduced motion → fades.
- Launch: the system splash shows the launcher mark still; on a cold start an in-app intro (`ui/intro/LaunchIntro.kt`) takes over at the same place and size, the OK button takes one breath (it warms and cools, Sacha's B), and the splash dissolves into the remote, which is already drawn underneath. About 0.9 s; a tap skips it; none with animations off. Ivory on ink in dark, ink on ivory in light.
- Performance target: visual and haptic response within one frame of a tap, before any network round trip.
- UI in English by default (`values/`), French as a translation (`values-fr/`); every string in both.
