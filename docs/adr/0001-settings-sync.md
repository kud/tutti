# ADR 0001: Sync settings through one file, in a folder first

- Status: accepted (2026-11-02); amended the same day: a folder first, the single file as the fallback
- Context: Erwann, 2026-11-02: « Je ne vois pas la partie synchronisation des settings, etc. sur Google Drive, comme on avait indiqué ». Until then tutti had only the manual Layout file (Settings › Remote & dock), which carries the layout alone and never syncs.

## Decision

tutti keeps its settings in **one file**, `tutti-sync.json`, in a place the person picks once through Android's own pickers (Storage Access Framework). Turning Sync on asks where, in one dialog, folder first:

1. **Choose a folder** (`ACTION_OPEN_DOCUMENT_TREE`), "On every phone, pick the same folder". tutti takes a persistable read/write grant on the tree, lists it and looks for `tutti-sync.json`: an exact, case-sensitive name among files, folders and " (1)" copies skipped, the most recently modified when several match. A folder already holding the file is joined like an existing file (every group stamped 0, so the file's settings come in); an empty folder gets a new file, which this phone's settings fill. A listing that fails is never read as "no file", since that would create a second one: the choice fails and any previous place carries on.
2. **Use an existing sync file** (`ACTION_OPEN_DOCUMENT`): its settings come in.
3. **Create a sync file** (`ACTION_CREATE_DOCUMENT`): this phone's settings fill it.

The settings remember which kind of place it is (`folder` and `file` in the `settings_sync` shared preferences). The storage app (Google Drive, or any DocumentsProvider) uploads the file. tutti adds no network code, no Google API, no account, so the `github` and `fdroid` flavours both have it unchanged.

### Why a folder first, and a file as the fallback

The first version asked for a single file only, on the strength of 2019–21 reports that Google Drive's DocumentsProvider stays out of the folder picker. That is wrong today: Drive lists its folders in `ACTION_OPEN_DOCUMENT_TREE`, seen on Erwann's phone on 2026-10-29 in pastille, and on 2026-11-02 he asked for tutti to work the same way (« on peut faire comme Pastille, ça marche bien »). A folder is the easier thing to get right on two phones: pick the same folder on each and tutti finds or creates the file, with no "create here, open there" to remember. Android has no picker that both opens and creates a file, and Drive allows two files with one name without saying so, which is why the file paths ask the person which one they mean. They stay for storage apps that offer no folders, listed after the folder, existing before new, so a second file where one exists takes a deliberate tap.

### Writing, and Drive's stale reads

- Every sync reads before it writes. The merged file is written only when its fingerprint (the file without `writtenBy`) differs from what the file holds; a sync with nothing new writes nothing, so Drive isn't asked to upload the same settings again. When the file still hashes to what this phone last read or wrote and the local settings match what they last agreed on, nothing is merged either.
- Writes open the document in `"wt"`, then `"rwt"` for a provider that refuses it. Plain `"w"` doesn't truncate on some Android versions, so a shorter file could keep the tail of a longer one; it is never used. The file is then read back, again after 0.5, 1.5 and 3 s while it differs; only a match records the content as agreed.
- Stale-read guard: before a write, the SHA-256 of what the file held is committed (`replaced_hash`, with `commit()` so a killed process still has it), along with the hash of what it should now hold (`seen_hash`). A later read returning exactly the replaced bytes is the provider's cache (Drive mid-upload), not another phone's change: it is neither merged nor written over, and the page says it is waiting for the storage app (`SyncProblem.WAITING`). The next sync reads again; once the file shows what was written, the guard clears.

### When

Every process start (`TuttiApplication`, which catches a change whose debounce the last process didn't outlive), the app coming to the front (`MainActivity.onStart`), every change to a synced value (debounced 2 s), and leaving the app (`onStop`, at once). Nothing is scheduled in the background.

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
- Unverified on a device: Drive's real round trip between two phones in one folder, how quickly Drive uploads after a write and surfaces the other phone's, whether Drive's `"wt"` is honoured, how long its cache serves the old bytes, and whether its folder listing reports `COLUMN_LAST_MODIFIED`. The first check is two phones picking the same Drive folder.
- A file deleted from the folder after it was found is not looked up again by name: sync reports it unreachable, and choosing the folder again finds or creates it.
