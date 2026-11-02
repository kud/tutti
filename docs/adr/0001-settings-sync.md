# ADR 0001: Sync settings through one file on Google Drive

- Status: accepted (2026-11-02)
- Context: Erwann, 2026-11-02: « Je ne vois pas la partie synchronisation des settings, etc. sur Google Drive, comme on avait indiqué ». Until then tutti had only the manual Layout file (Settings › Remote & dock), which carries the layout alone and never syncs.

## Decision

tutti keeps its settings in **one file**, `tutti-sync.json`, that the person picks once through Android's own file picker (Storage Access Framework): **Create a sync file** on the first phone (`ACTION_CREATE_DOCUMENT`), **Use an existing sync file** on the next one or after a reinstall (`ACTION_OPEN_DOCUMENT`). tutti takes a persistable read/write grant on that document and merges with it after a change (debounced 2 s), when the app comes to the front, and when it leaves. The storage app (Google Drive, or any DocumentsProvider) uploads it. tutti adds no network code, no Google API, no account, so the `github` and `fdroid` flavours both have it unchanged.

### Why a file, not a folder

Brume and pastille ask for a folder (`ACTION_OPEN_DOCUMENT_TREE`). Google Drive's DocumentsProvider does not take part in that picker: its roots don't declare `Root.FLAG_SUPPORTS_IS_CHILD`, which the system picker requires before it lists a root for folder picking, so Drive is absent or greyed out there. It does offer single documents to `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`, with persistable grants. Drive being the storage Erwann named, a single document is the path that works with it; it works with every other provider too. Writes open the document in `"wt"` and fall back to `"w"` for a provider that refuses truncation.

### Format and merge

- `:protocol` `sync/SyncFile.kt`: `{"format": "tutti-sync", "schema": 1, "writtenBy", "groups": {name: {"at", "data"}}}`. A file of another format, or a newer schema, is refused and never written over. Unknown groups and fields are carried through, so an older tutti never strips what a newer one wrote.
- Four groups, each travelling whole: `remote` (the layout and the dock, so pins and long presses stay coherent), `scenes`, `profiles` (per-app profiles), `preferences` (`AppPrefs` minus what stays on the phone).
- Per group, the later change wins. Each phone stamps a group with the time it saw its value change (a hash of the group, kept in the `settings_sync` shared preferences). Joining an existing file stamps every group 0, so the file's settings come in rather than a fresh phone's defaults. Ties break on the values, never on which side is local, so two phones write the same file (`SyncMergeTest`).
- Scenes and the dock travel together because a pin runs a scene by id; the Layout file still names scenes, since it moves between phones without them.

### What never syncs

Devices and their hosts, pairing material, the TV PSK, Sony tokens and session keys (SecureStore: `files/secure`, `secure.preferences_pb`), computers, the TV's app list, setup done, developer mode, the one-time tips, the bar's last volume and tonight's cap. `SyncGroups` reads and writes only the stored `prefs`, `dock`, `scenes` and `profiles` keys, and drops `SyncGroups.LOCAL_PREFS`; the page lists both sides in words. `settings_sync.xml` is excluded from backup: a document grant doesn't survive a restore, so a restored phone joins the file again.

### The Layout file

It stays as it was: a manual export to share or keep a copy of the layout. Sync is the automatic path for everything.

## Consequences

- Upgrades: no stored format changes. Sync state lives in its own shared preferences; a phone that never opens Sync behaves as before.
- Unverified on a device: Drive's real round trip between two phones, how quickly Drive uploads after a write, whether Drive's `"wt"` is honoured, and Drive serving a cached copy until it refreshes. The first check is two phones on one file.
