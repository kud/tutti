# Design direction (Iris)

The app replaces BRAVIA Connect day to day, with a Google TV–grade remote at its heart. Setup and room calibration stay in BRAVIA Connect.

## Information architecture

Three places, opening on the remote.

1. **Remote (home)**
   - Top: one system chip, "Salon · BRAVIA 8 + Bar 9", single status dot; it splits into two lines only when one device drops ("Bar 9 injoignable"). One Power button switches both.
   - Below: a horizontal strip of scene pills.
   - Centre: the pad, the largest element. Touchpad or D-pad, switchable in one move. Tap = OK. Long-press Select is reserved for the TV (sends a long press).
   - Under the pad, outside its surface: the mode switch (K1), a strip with a cell per mode and a thumb under the current one, told by the thumb and a solid icon against an outline one, never colour. A tap on the other cell switches, with a haptic; the pad itself is never swiped to change mode, since its swipes are navigation. At least 32 dp clear of the button row, its touch target no taller than it is drawn. One switch to TalkBack: double tap toggles, an action picks each mode.
   - Under the pad: Back, Home, Play/Pause, Mic, Keyboard, Mute. The mic is hold to talk to the TV's assistant: it grows and fills with the primary colour while listening, a pill above it reads "À l'écoute… relâchez pour envoyer"; sliding a finger off turns it to the error container ("Relâchez pour annuler"), with a tick at the edge. A tap shorter than 300 ms sends nothing and explains the gesture. (Maya's placement, pending Iris's review.)
   - Right edge: vertical volume rail (soundbar volume, absolute).
   - Bottom: a dock of 4 shortcut slots.
   - Peek sheet "Son" at the bottom; pulled up it shows sound controls.
2. **Scenes**: gallery and editor.
3. **Réglages**: devices and pairing, TV settings (picture, sound output, inputs, power saving), soundbar system settings, app preferences, haptics.

One tap from the remote: power, volume, mute, input, keyboard, any scene, any shortcut, Night, Voice.
Two levels deep at most.

### "Son" sheet
Sound field, Voice, Night, DRC, Auto volume, IMAX, 360 Spatial as large toggles; Bass and Rear level as sliders; Voice Zoom 3 as a level slider only if the bar answers the capability probe. A greyed setting always shows its reason ("Voix désactivée : virtualiseur actif"), never hidden. "Recalibrer dans BRAVIA Connect" deep link.

### Shortcuts
- Dock: four pins a page, up to three pages (swipe; dots only from page 2). Each pin has a name, an icon or two letters, a tap action (one action, or ordered steps with waits, tried from the editor with "Try") and its own long-press action, offered first in a menu above "Edit this pin".
- Long-press on any dock slot or secondary button (Home, Back, Play) assigns a second action.
- Pad gestures, two only: two-finger tap = play/pause; two-finger vertical drag = bar volume.

### Edit mode
- Settings › Shortcuts › "Remote layout", or "Edit the remote layout" at the foot of a pin's editor, turns the remote into its own edit mode: the same screen, what can change in a dashed frame, what can't under a lock. Changes save as they are made; "Done" (or Back) leaves.
- Adjustable: the round-button row (up to six, from Back, Home, Play/Pause, Voice, Keyboard, Mute, Info, Subtitles, Input, Guide, TV settings; drag sideways or TalkBack's move left / right), the scene strip (shown or hidden), the pad in three steps (Compact stands a shorter pad at the rail's foot, Normal is the pad as it shipped, Large takes the margins back), and the pins' order within a page (drag, or move left / right; tap opens the pin's editor).
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
