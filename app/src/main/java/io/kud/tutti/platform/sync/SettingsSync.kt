package io.kud.tutti.platform.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.BuildConfig
import io.kud.tutti.platform.data.SyncGroups
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.protocol.sync.SyncCodec
import io.kud.tutti.protocol.sync.SyncFile
import io.kud.tutti.protocol.sync.SyncGroup
import io.kud.tutti.protocol.sync.SyncMerge
import io.kud.tutti.protocol.sync.SyncRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why the last sync didn't work, said in words on the screen. [WAITING]: the storage app still
 * serves what the file held before this phone's last write (Drive mid-upload); the next sync retries.
 */
enum class SyncProblem { UNREACHABLE, NEWER, NOT_OURS, WAITING }

data class SyncState(
    val hasFile: Boolean = false,
    val fileName: String? = null,
    /** The folder the file lives in, when the place is a folder; null for a single picked file. */
    val folderName: String? = null,
    val auto: Boolean = false,
    val lastSyncedAt: Long? = null,
    val problem: SyncProblem? = null,
)

/**
 * Settings › Sync (docs/adr/0001-settings-sync.md). The place is picked once through the system's
 * picker: a folder first (tutti finds or creates [SyncFile.NAME] in it), else a single file, existing
 * or new. tutti keeps it merged with this phone's settings: at process start, when the app comes to
 * the front, after a change (debounced) and when it leaves. The storage app (Google Drive…) uploads
 * it; tutti has no network code here.
 *
 * Each group's time is this phone's own: when a group's value differs from the one last seen, it
 * changed here, now. Joining an existing file stamps every group 0, so the file's settings win.
 */
@Singleton
class SettingsSync @Inject constructor(
    @ApplicationContext context: Context,
    private val store: TuttiStore,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SyncState> = _state

    private val file: Uri? get() = prefs.getString(FILE, null)?.let(Uri::parse)
    private val folder: Uri? get() = prefs.getString(FOLDER, null)?.let(Uri::parse)
    private val active get() = file != null && prefs.getBoolean(AUTO, false)

    /** Once per process: watch the synced values and settle requests into one merge. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { store.syncRaw.drop(1).collect { request() } }
        scope.launch { requests.debounce(DEBOUNCE_MS).collect { runSync() } }
    }

    /** Asks for a merge soon. Does nothing unless a place is chosen and sync is on. */
    fun request() { if (active) requests.tryEmit(Unit) }

    /** The app left the screen: merge now rather than wait for the debounce. */
    fun flush() { if (active) scope.launch { runSync() } }

    /**
     * Starts syncing with a folder ([tree], from ACTION_OPEN_DOCUMENT_TREE): [SyncFile.NAME] in it is
     * joined like an existing file when it is there, and created like a new one when it isn't. False
     * when the folder can't be listed or the file can't be made; then any previous place carries on.
     */
    suspend fun chooseFolder(tree: Uri): Boolean {
        val resolved = try {
            app.contentResolver.takePersistableUriPermission(tree, GRANT)
            withContext(Dispatchers.IO) { SyncDocument.resolve(app, tree) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (tree != folder) release(tree)
            _state.value = readState().copy(problem = SyncProblem.UNREACHABLE)
            return false
        }
        return adopt(resolved.uri, resolved.created, tree)
    }

    /**
     * Starts syncing with the single file [uri]. [created]: a file just made on this phone, whose
     * settings fill it; otherwise an existing file, whose settings come in. False when the file can't
     * be used, and then any previous place carries on.
     */
    suspend fun choose(uri: Uri, created: Boolean): Boolean {
        val ok = runCatching { app.contentResolver.takePersistableUriPermission(uri, GRANT) }.isSuccess
        if (!ok) {
            _state.value = readState().copy(problem = SyncProblem.UNREACHABLE)
            return false
        }
        return adopt(uri, created, tree = null)
    }

    private suspend fun adopt(uri: Uri, created: Boolean, tree: Uri?): Boolean {
        val readable = runCatching { withContext(Dispatchers.IO) { SyncDocument.read(app, uri) } }.isSuccess
        if (!readable) {
            if (tree == null && uri != file) release(uri)
            if (tree != null && tree != folder) release(tree)
            _state.value = readState().copy(problem = SyncProblem.UNREACHABLE)
            return false
        }
        // A file picked on its own holds its own grant; one inside a folder lives on the folder's.
        file?.takeIf { it != uri && folder == null }?.let(::release)
        folder?.takeIf { it != tree }?.let(::release)
        val edit = prefs.edit()
            .putString(FILE, uri.toString())
            .putString(FILE_NAME, SyncDocument.name(app, uri) ?: SyncFile.NAME)
            .putBoolean(AUTO, true)
            .remove(PROBLEM).remove(LAST).remove(SEEN).remove(REPLACED).remove(AGREED)
        if (tree != null) {
            edit.putString(FOLDER, tree.toString()).putString(FOLDER_NAME, SyncDocument.folderName(app, tree))
        } else {
            edit.remove(FOLDER).remove(FOLDER_NAME)
        }
        val now = System.currentTimeMillis()
        SyncGroups.read(store.syncRaw.first()).forEach { (key, data) ->
            edit.putString(HASH + key, hash(data)).putLong(AT + key, if (created) now else 0L)
        }
        edit.apply()
        _state.value = readState()
        return runSync()
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean(AUTO, on).apply()
        _state.value = readState()
        if (on) request()
    }

    /** One merge now, whether or not automatic sync is on. True when it worked. */
    suspend fun syncNow(): Boolean = runSync()

    /** Stops syncing. The file stays where it is and this phone keeps its settings. */
    fun stop() {
        file?.let(::release)
        folder?.let(::release)
        prefs.edit().clear().apply()
        _state.value = readState()
    }

    private fun release(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, GRANT) }
    }

    /** Reads and writes go through the storage app, which may be slow: never on the caller's thread. */
    private suspend fun runSync(): Boolean = withContext(Dispatchers.IO) { syncLocked() }

    private suspend fun syncLocked(): Boolean = mutex.withLock {
        val uri = file ?: return@withLock false
        val problem = try {
            val text = SyncDocument.read(app, uri)
            val textHash = sha(text)
            val seen = prefs.getString(SEEN, null)
            if (SyncRules.isStaleRead(textHash, prefs.getString(REPLACED, null), seen)) {
                // The provider's cache, not news: merging it would only replay what this phone replaced.
                SyncProblem.WAITING
            } else {
                if (textHash == seen) prefs.edit().remove(REPLACED).apply()
                val local = localFile()
                if (textHash == seen && fingerprint(local) == prefs.getString(AGREED, null)) {
                    null
                } else {
                    when (val read = SyncCodec.decode(text)) {
                        SyncRead.NotOurs -> SyncProblem.NOT_OURS
                        is SyncRead.Newer -> SyncProblem.NEWER
                        SyncRead.Empty -> write(uri, textHash, local)
                        is SyncRead.Ok -> {
                            val merged = SyncMerge.merge(local, read.file, BuildConfig.VERSION_NAME)
                            val incoming = SyncMerge.incoming(local, merged)
                            if (incoming.isNotEmpty()) take(incoming)
                            val print = fingerprint(merged)
                            if (print != fingerprint(read.file)) {
                                write(uri, textHash, merged)
                            } else {
                                // Nothing to add: the file already holds it, so nothing is written.
                                prefs.edit().putString(SEEN, textHash).putString(AGREED, print).apply()
                                null
                            }
                        }
                    }
                }
            }
        } catch (error: IOException) {
            SyncProblem.UNREACHABLE
        } catch (error: SecurityException) {
            SyncProblem.UNREACHABLE
        } catch (error: IllegalArgumentException) {
            SyncProblem.UNREACHABLE
        }
        val edit = prefs.edit()
        if (problem == null) edit.putLong(LAST, System.currentTimeMillis()).remove(PROBLEM) else edit.putString(PROBLEM, problem.name)
        edit.apply()
        _state.value = readState()
        problem == null
    }

    /**
     * Writes [next] over the file, which held content hashing to [oldHash]. What it replaces and what
     * it should now hold are committed first, so a later read of the old bytes is known for the
     * provider's cache even if the process dies mid-write. Null when the file reads back as written.
     */
    private suspend fun write(uri: Uri, oldHash: String, next: SyncFile): SyncProblem? {
        val text = SyncCodec.encode(next)
        val newHash = sha(text)
        prefs.edit().putString(REPLACED, oldHash).putString(SEEN, newHash).commit()
        SyncDocument.write(app, uri, text)
        val back = SyncDocument.readBack(app, uri, text)
        return when {
            back == text -> {
                prefs.edit().putString(AGREED, fingerprint(next)).apply()
                null
            }
            back == null -> SyncProblem.UNREACHABLE
            // The old bytes, or another phone's write landing meanwhile: nothing is recorded as
            // agreed, and the next sync reads the file again (the old bytes are then a stale read).
            else -> SyncProblem.WAITING
        }
    }

    /** This phone's groups, each stamped with when it last changed here. */
    private suspend fun localFile(): SyncFile {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        val groups = SyncGroups.read(store.syncRaw.first()).mapValues { (key, data) ->
            val digest = hash(data)
            val at = if (prefs.getString(HASH + key, null) == digest) {
                prefs.getLong(AT + key, 0L)
            } else {
                edit.putString(HASH + key, digest).putLong(AT + key, now)
                now
            }
            SyncGroup(at, data)
        }
        edit.apply()
        return SyncFile(writtenBy = BuildConfig.VERSION_NAME, groups = groups)
    }

    /** Takes [incoming] in, then records the result as seen at the file's time, so it isn't restamped. */
    private suspend fun take(incoming: Map<String, SyncGroup>) {
        store.applySynced(incoming.mapValues { it.value.data })
        val after = SyncGroups.read(store.syncRaw.first())
        val edit = prefs.edit()
        for ((key, group) in incoming) {
            val data = after[key] ?: continue
            edit.putString(HASH + key, hash(data)).putLong(AT + key, group.updatedAt)
        }
        edit.apply()
    }

    private fun hash(data: JsonElement): String = sha(data.toString())

    /** What a file holds, leaving out who wrote it: two files with this equal need no write. */
    private fun fingerprint(file: SyncFile): String = sha(SyncCodec.encode(file.copy(writtenBy = null)))

    private fun readState() = SyncState(
        hasFile = prefs.getString(FILE, null) != null,
        fileName = prefs.getString(FILE_NAME, null),
        folderName = prefs.getString(FOLDER_NAME, null),
        auto = prefs.getBoolean(AUTO, false),
        lastSyncedAt = prefs.getLong(LAST, 0L).takeIf { it > 0 },
        problem = prefs.getString(PROBLEM, null)?.let { p -> SyncProblem.entries.firstOrNull { it.name == p } },
    )

    private companion object {
        /** Excluded from backup: a restored phone joins the place again rather than trust a stale grant. */
        const val PREFS_NAME = "settings_sync"
        const val GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        const val DEBOUNCE_MS = 2_000L
        const val FILE = "file"
        const val FILE_NAME = "file_name"
        /** The folder tree, when the place is a folder; the file is then [FILE] inside it. */
        const val FOLDER = "folder"
        const val FOLDER_NAME = "folder_name"
        const val AUTO = "auto"
        const val LAST = "last_synced_at"
        const val PROBLEM = "problem"
        const val HASH = "hash_"
        const val AT = "at_"
        /** SHA-256 of what the file holds as far as this phone knows: last read, or last written. */
        const val SEEN = "seen_hash"
        /** SHA-256 of what the file held before this phone's last write: reading it again is a stale cache. */
        const val REPLACED = "replaced_hash"
        /** Fingerprint of the content this phone and the file last agreed on. */
        const val AGREED = "agreed_fingerprint"
    }
}

internal fun sha(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/** One entry of a folder, as the provider lists it. */
internal data class SyncChild(val documentId: String, val name: String?, val modified: Long?, val isDirectory: Boolean = false)

/** Pure rules, kept apart from the provider so they can be tested on the JVM. */
internal object SyncRules {
    /**
     * The sync file among a folder's children: an exact, case-sensitive match on [SyncFile.NAME]
     * among files (folders and " (1)" copies skipped). Several matches use the most recently
     * modified; an unknown time counts as oldest, and a tie keeps the provider's order. Null: none.
     */
    fun pick(children: List<SyncChild>): String? =
        children.withIndex()
            .filter { !it.value.isDirectory && it.value.name == SyncFile.NAME }
            .maxWithOrNull(compareBy<IndexedValue<SyncChild>> { it.value.modified?.takeIf { m -> m > 0 } ?: Long.MIN_VALUE }.thenByDescending { it.index })
            ?.value?.documentId

    /**
     * A read returning exactly what this phone's last write replaced is the provider's cache (Drive
     * mid-upload), not a change from another phone, unless that is also what was written.
     */
    fun isStaleRead(hash: String, replacedHash: String?, writtenHash: String?): Boolean =
        replacedHash != null && hash == replacedHash && hash != writtenHash
}

/** The one document, through its DocumentsProvider. */
internal object SyncDocument {
    data class Resolved(val uri: Uri, val created: Boolean)

    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw FileNotFoundException(uri.toString())

    /**
     * "wt" truncates, so a shorter file never keeps the tail of a longer one. A provider that refuses
     * it gets "rwt". Plain "w" doesn't truncate on some Android versions, so it is never used.
     */
    fun write(context: Context, uri: Uri, text: String) {
        val bytes = text.encodeToByteArray()
        val stream = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (error: IllegalArgumentException) {
            null
        } catch (error: UnsupportedOperationException) {
            null
        } catch (error: FileNotFoundException) {
            null
        } ?: context.contentResolver.openOutputStream(uri, "rwt")
        stream?.use { it.write(bytes) } ?: throw FileNotFoundException(uri.toString())
    }

    /**
     * Reads the file back after a write: at once, then after 0.5, 1.5 and 3 s while it differs, since
     * a cloud provider may serve the old bytes while it uploads. Returns the last content read, null
     * when the last read failed.
     */
    suspend fun readBack(context: Context, uri: Uri, text: String): String? {
        var last: String? = null
        for (wait in READ_BACK_MS) {
            if (wait > 0) delay(wait)
            last = runCatching { read(context, uri) }.getOrNull()
            if (last == text) return last
        }
        return last
    }

    private val READ_BACK_MS = longArrayOf(0, 500, 1_000, 1_500)

    /**
     * Finds or creates [SyncFile.NAME] in the folder [tree]. Throws when the folder can't be listed:
     * a listing that failed is never read as "no file", which would create a second one.
     */
    fun resolve(context: Context, tree: Uri): Resolved {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val children = resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            val found = mutableListOf<SyncChild>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                found += SyncChild(
                    documentId = id,
                    name = cursor.getString(1),
                    modified = if (cursor.isNull(2)) null else cursor.getLong(2),
                    isDirectory = cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
            found
        } ?: throw IOException("Folder listing unavailable")
        SyncRules.pick(children)?.let { id -> return Resolved(DocumentsContract.buildDocumentUriUsingTree(tree, id), created = false) }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, SyncFile.MIME, SyncFile.NAME)
            ?: throw FileNotFoundException("Could not create ${SyncFile.NAME}")
        return Resolved(created, created = true)
    }

    fun name(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** The folder's name as the provider shows it, or null when it doesn't say. */
    fun folderName(context: Context, tree: Uri): String? = runCatching {
        name(context, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
    }.getOrNull()
}
