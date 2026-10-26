# Settings, arranged like Android's (proposal, Iris, 2026-10-26)

Erwann, 2026-10-26: « Dans settings je crois que advanced devrait être un second screen plutôt qu'un collapse nan. […] On devrait commencer à ranger comme dans les settings de Android imo. »

Today Settings is one long scroll of eleven groups, and TV settings is another long scroll with Advanced as a fold of about 48 rows. Proposal: a short top-level list, one row per category (icon, name, one live line saying where things stand), each opening its own screen. Nothing changes in what a setting does, only where it lives.

## The tree

```
Settings
├─ What tutti can do        "3 of 5 done · IP control next"       (stays first: it is how you unlock the rest)
├─ Devices                  "BRAVIA 8 · Bar 9 · MacBook"
│    TV, soundbar and computers (status, pair again, IP control, Bluetooth backup, forget),
│    Add a device, Set up again, Sony account
├─ TV                       "Standard · Power saving off"  or  "IP control isn't answering"
│  ├─ Picture               picture mode, scene select, brightness, contrast, colour, colour temperature, Motionflow
│  │  └─ Advanced picture   its own screen: Sony's sections as headings (Brightness, Colour, White balance,
│  │                        Colour adjustment, Clarity, Motion, Video, Other), no fold
│  ├─ Sound                 TV sound (output, sound mode…) and speakers (position, subwoofer)
│  ├─ Inputs                the inputs, the current one ticked
│  ├─ Power                 power saving, Wake-on-LAN, sleep timer
│  └─ About this TV         model, generation
├─ Soundbar                 "Bar 9 · TV input"
│    the bar's settings as today, hidden inputs, Recalibrate in BRAVIA Connect
├─ Remote & dock            "8 pins · 2 long presses set"
│    dock layout, long press per button, apps shown, volume guard
├─ Display & feedback       "Dark · vibration on"
│    theme, true black, dark room, vibration, key sounds
├─ Advanced                 "Developer mode off"
│    developer mode, diagnostic, technical details
└─ About
```

Eight rows: the whole top level fits on a phone without scrolling. Every screen is at most three taps from Settings (Settings › TV › Picture › Advanced picture is the deepest).

## The three decisions that matter

1. **TV opens a hub, not a long page.** Settings › TV lists Picture, Sound, Inputs, Power, About this TV, each with its own live line ("Standard · brightness 32"). That is Sony's own menu shape and Android's, every screen stays short, and it gives the part-by-part load a natural home: the hub opens at once and asks the TV for everything; a part still on its way says "Waiting for the TV…" on its row, and inside its screen the waiting rows hold their places as they do now. Without IP control, the TV row says so in words and the hub shows today's explainer instead of the parts.

2. **Advanced picture is one screen with headings, not one screen per section.** A screen per section (Colour, Motion…) would be a fourth level for groups of three to eight rows. One screen, Sony's order, section headings, reached from the last row of Picture ("Advanced picture · 48 settings"). The fold and its toggle go away.

3. **Devices manages devices; TV and Soundbar set them.** Pairing, IP control, Bluetooth backup and Forget live in Devices; what the TV or the bar does lives under its own name. Android splits "Connected devices" from what they do the same way, and today the two are interleaved on one page. The Connexions sheet on the remote keeps its job (what works right now) and its « Configurer » buttons still land on What tutti can do.

## Smaller calls, already decided unless Erwann objects

- **Unavailable settings:** hidden by default as today, with "Show unavailable settings" and its count at the foot of each screen that has any (Picture, Advanced picture, Sound), since the count only makes sense there. Shown, they keep their place, faded, with the reason under them.
- **No search, for now.** About 25 app settings and 60 TV settings, all named in Sony's words, at most three levels deep: a search bar is more than it is worth. If one ever earns its place, it goes in Advanced picture only.
- **Live lines say state in words**, never a colour or a dot alone, and never more than one line.
- **Short choices are segments** (shipped on `iris-segments`), on every screen.

## Recommendation

Build it in two steps: first the TV hub with Advanced picture as its own screen (it answers Erwann's point directly and touches only TV settings), then the top-level list for Settings. Each step ships on its own as a dev release.
