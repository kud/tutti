package io.kud.tutti.platform.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Why the last sync didn't work, said in words on the screen. */
enum class SyncProblem { UNREACHABLE, NEWER, NOT_OURS }

data class SyncState(
    val hasFile: Boolean = false,
    val fileName: String? = null,
    val auto: Boolean = false,
    val lastSyncedAt: Long? = null,
    val problem: SyncProblem? = null,
)

/**
 * Settings › Sync (docs/adr/0001-settings-sync.md). The person picks one file once, through the
 * system's picker: a new one on the first phone, the same one on the next. tutti keeps it merged
 * with this phone's settings, after a change (debounced), when the app comes to the front and
 * when it leaves. The storage app (Google Drive…) uploads it; tutti has no network code here.
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
    private val active get() = file != null && prefs.getBoolean(AUTO, false)

    /** Once per process: watch the synced values and settle requests into one merge. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { store.syncRaw.drop(1).collect { request() } }
        scope.launch { requests.debounce(DEBOUNCE_MS).collect { runSync() } }
    }

    /** Asks for a merge soon. Does nothing unless a file is chosen and sync is on. */
    fun request() { if (active) requests.tryEmit(Unit) }

    /** The app left the screen: merge now rather than wait for the debounce. */
    fun flush() { if (active) scope.launch { runSync() } }

    /**
     * Starts syncing with [uri]. [created]: a file just made on this phone, whose settings fill it;
     * otherwise an existing file, whose settings come in. False when the file can't be used, and
     * then any previous file carries on.
     */
    suspend fun choose(uri: Uri, created: Boolean): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching {
            app.contentResolver.takePersistableUriPermission(uri, flags)
            SyncDocument.read(app, uri)
        }.isSuccess
        if (!ok) {
            _state.value = readState().copy(problem = SyncProblem.UNREACHABLE)
            return false
        }
        file?.takeIf { it != uri }?.let { old -> runCatching { app.contentResolver.releasePersistableUriPermission(old, flags) } }
        val edit = prefs.edit().putString(FILE, uri.toString()).putString(FILE_NAME, SyncDocument.name(app, uri))
            .putBoolean(AUTO, true).remove(PROBLEM).remove(LAST)
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
        file?.let { uri ->
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        prefs.edit().clear().apply()
        _state.value = readState()
    }

    private suspend fun runSync(): Boolean = mutex.withLock {
        val uri = file ?: return@withLock false
        val problem = try {
            val local = localFile()
            when (val read = SyncCodec.decode(SyncDocument.read(app, uri))) {
                SyncRead.NotOurs -> SyncProblem.NOT_OURS
                is SyncRead.Newer -> SyncProblem.NEWER
                SyncRead.Empty -> null.also { SyncDocument.write(app, uri, SyncCodec.encode(local)) }
                is SyncRead.Ok -> {
                    val merged = SyncMerge.merge(local, read.file, BuildConfig.VERSION_NAME)
                    val incoming = SyncMerge.incoming(local, merged)
                    if (incoming.isNotEmpty()) take(incoming)
                    if (!SyncMerge.sameContent(read.file, merged)) SyncDocument.write(app, uri, SyncCodec.encode(merged))
                    null
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

    private fun hash(data: JsonElement): String =
        MessageDigest.getInstance("SHA-256").digest(data.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private fun readState() = SyncState(
        hasFile = prefs.getString(FILE, null) != null,
        fileName = prefs.getString(FILE_NAME, null),
        auto = prefs.getBoolean(AUTO, false),
        lastSyncedAt = prefs.getLong(LAST, 0L).takeIf { it > 0 },
        problem = prefs.getString(PROBLEM, null)?.let { p -> SyncProblem.entries.firstOrNull { it.name == p } },
    )

    private companion object {
        /** Excluded from backup: a restored phone joins the file again rather than trust a stale grant. */
        const val PREFS_NAME = "settings_sync"
        const val DEBOUNCE_MS = 2_000L
        const val FILE = "file"
        const val FILE_NAME = "file_name"
        const val AUTO = "auto"
        const val LAST = "last_synced_at"
        const val PROBLEM = "problem"
        const val HASH = "hash_"
        const val AT = "at_"
    }
}

/**
 * The one document, through its DocumentsProvider. Google Drive's provider offers single documents
 * (create, open, persistable grants) but no folder picking, so tutti never asks for a folder.
 */
internal object SyncDocument {
    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw FileNotFoundException(uri.toString())

    /**
     * "wt" truncates, so a shorter file never keeps the tail of a longer one. A provider that refuses
     * the mode gets "w", which Drive treats as a whole new upload of the content.
     */
    fun write(context: Context, uri: Uri, text: String) {
        val bytes = text.encodeToByteArray()
        val stream = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (error: IllegalArgumentException) {
            null
        } catch (error: UnsupportedOperationException) {
            null
        } ?: context.contentResolver.openOutputStream(uri, "w")
        stream?.use { it.write(bytes) } ?: throw FileNotFoundException(uri.toString())
    }

    fun name(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
