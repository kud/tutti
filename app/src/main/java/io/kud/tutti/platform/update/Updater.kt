package io.kud.tutti.platform.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.kud.tutti.BuildConfig
import io.kud.tutti.protocol.update.AppVersion
import io.kud.tutti.protocol.update.CheckSchedule
import io.kud.tutti.protocol.update.Flavour
import io.kud.tutti.protocol.update.Hosts
import io.kud.tutti.protocol.update.ReleaseFeed
import io.kud.tutti.protocol.update.Sha256
import io.kud.tutti.protocol.update.UpdateCue
import io.kud.tutti.protocol.update.Update
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Why a step failed, each said in words by the About screen (never colour alone). */
enum class UpdateProblem { NETWORK, FEED, DOWNLOAD, DIGEST, PACKAGE, INSTALL }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: Update) : UpdateState
    /** [percent] is null while GitHub has not said how big the file is. */
    data class Downloading(val update: Update, val percent: Int?) : UpdateState
    /** Downloaded and verified; Android must first let this app install packages. */
    data class NeedsPermission(val update: Update) : UpdateState
    data class Installing(val update: Update) : UpdateState
    /** [update] is kept so "Try again" knows what to retry; [detail] is Android's own words, if any. */
    data class Failed(val problem: UpdateProblem, val update: Update?, val detail: String? = null) : UpdateState
}

/**
 * The in-app updater (docs/architecture.md, "In-app updates"): asks GitHub's releases API on
 * demand, and at most once a day when the app opens; downloads only on a tap, only from GitHub's
 * hosts, checks the SHA-256 GitHub reported and that the file is a newer build of this very app,
 * then hands it to Android's PackageInstaller, which asks the person to confirm. Never in the
 * background, never an install without that confirmation.
 */
@Singleton
class Updater @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val busy = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // System trust store, normal verification; redirects are followed by hand so every hop is checked.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val installed: String get() = BuildConfig.VERSION_NAME
    // The build type, not DEBUG: a dev pre-release is the debug build made non-debuggable.
    private val flavour = if (BuildConfig.BUILD_TYPE == "debug") Flavour.DEBUG else Flavour.RELEASE
    private val current: AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0, 0)

    /**
     * The newest build the last check found, kept across restarts so the cue outlives the process,
     * and the build set aside with "Later" (docs/design.md, "Updates").
     */
    private val _found = MutableStateFlow(readVersion(KEY_FOUND))
    private val _setAside = MutableStateFlow(readVersion(KEY_SET_ASIDE))

    /** A newer build than the installed one, if a check found one, whether or not it was set aside. */
    val newer: StateFlow<AppVersion?> get() = _newer
    private val _newer = MutableStateFlow(_found.value?.takeIf { it > current })

    /** How loudly that build makes itself known: the remote's dot only for [UpdateCue.NOTICE]. */
    val cue: StateFlow<UpdateCue> get() = _cue
    private val _cue = MutableStateFlow(UpdateCue.of(current, _found.value, _setAside.value))

    /** "Later": the dot goes for this build; the ⋮ menu's row and About still say it. A newer build notices again. */
    fun setAside(version: AppVersion) {
        prefs.edit().putString(KEY_SET_ASIDE, version.toString()).apply()
        _setAside.value = version
        refreshCue()
    }

    /**
     * The automatic check, from the app being opened or coming back: at most once an hour for a dev
     * build, once a day for a final release ([CheckSchedule.intervalFor]). Returns a newer release
     * only the first time that build is found, so its message shows once.
     */
    suspend fun checkIfDue(): Update? {
        val last = prefs.getLong(KEY_LAST_CHECK, -1L).takeIf { it >= 0 }
        if (!CheckSchedule.isDue(last, System.currentTimeMillis(), CheckSchedule.intervalFor(current))) return null
        val before = _found.value
        val update = check() ?: return null
        return update.takeIf { UpdateCue.announces(it.version, before) }
    }

    private fun rememberFound(found: AppVersion?) {
        prefs.edit().apply { if (found == null) remove(KEY_FOUND) else putString(KEY_FOUND, found.toString()) }.apply()
        _found.value = found
        refreshCue()
    }

    private fun refreshCue() {
        _newer.value = _found.value?.takeIf { it > current }
        _cue.value = UpdateCue.of(current, _found.value, _setAside.value)
    }

    private fun readVersion(key: String): AppVersion? = prefs.getString(key, null)?.let { AppVersion.parse(it) }

    /** Asks GitHub now. Leaves a download or install in progress alone. */
    suspend fun check(): Update? = busy.withLock {
        _state.value = UpdateState.Checking
        val outcome = withContext(Dispatchers.IO) {
            runCatching { get(ReleaseFeed.URL, api = true).use { it.body?.string() ?: "" } }
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val body = outcome.getOrElse {
            _state.value = UpdateState.Failed(UpdateProblem.NETWORK, null)
            return null
        }
        val releases = ReleaseFeed.parse(body) ?: run {
            _state.value = UpdateState.Failed(UpdateProblem.FEED, null)
            return null
        }
        val update = ReleaseFeed.pickUpdate(current, releases, flavour)
        _state.value = if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
        rememberFound(update?.version)
        if (update == null) withContext(Dispatchers.IO) { apkFile().delete() }
        update
    }

    /** Downloads [update], verifies it, then installs it (or asks for the install permission first). */
    suspend fun downloadAndInstall(update: Update) {
        busy.withLock {
            val file = apkFile()
            val problem = withContext(Dispatchers.IO) { download(update, file) }
            if (problem != null) {
                file.delete()
                _state.value = UpdateState.Failed(problem, update)
                return
            }
        }
        install(update)
    }

    /** Installs the verified file, or says Android's permission is needed first. */
    fun install(update: Update) {
        val file = apkFile()
        if (!file.isFile) {
            _state.value = UpdateState.Failed(UpdateProblem.DOWNLOAD, update)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            return
        }
        _state.value = UpdateState.Installing(update)
        runCatching { commit(file) }.onFailure {
            _state.value = UpdateState.Failed(UpdateProblem.INSTALL, update, it.message)
        }
    }

    /** Android's "Install unknown apps" page for this app. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** The install session ended without installing (cancelled or refused): say so, keep the file to retry. */
    internal fun onInstallFinished(status: Int, message: String?) {
        val update = (state.value as? UpdateState.Installing)?.update
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                update?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> UpdateState.Failed(UpdateProblem.INSTALL, update, message)
        }
    }

    private fun download(update: Update, file: File): UpdateProblem? {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            get(update.asset.url, api = false).use { response ->
                val body = response.body ?: return UpdateProblem.DOWNLOAD
                val total = body.contentLength().takeIf { it > 0 } ?: update.asset.size.takeIf { it > 0 }
                if (total != null && total > MAX_APK_BYTES) return UpdateProblem.PACKAGE
                file.parentFile?.mkdirs()
                var read = 0L
                var shown = -1
                _state.value = UpdateState.Downloading(update, total?.let { 0 })
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > MAX_APK_BYTES) return UpdateProblem.PACKAGE
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val percent = total?.let { ((read * 100) / it).toInt().coerceIn(0, 100) }
                            if (percent != null && percent != shown) {
                                shown = percent
                                _state.value = UpdateState.Downloading(update, percent)
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            return UpdateProblem.DOWNLOAD
        }
        if (!Sha256.matches(update.sha256, digest.digest())) return UpdateProblem.DIGEST
        return if (isNewerBuildOfThisApp(file)) null else UpdateProblem.PACKAGE
    }

    /** Same package name and a higher versionCode: anything else is never handed to the installer. */
    private fun isNewerBuildOfThisApp(file: File): Boolean {
        val pm = context.packageManager
        val archive: PackageInfo = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, 0)
        }) ?: return false
        return archive.packageName == context.packageName && archive.longVersionCode > BuildConfig.VERSION_CODE
    }

    /** GET [url] over HTTPS, following at most [Hosts.MAX_REDIRECTS] redirects, each to an allowed host. */
    private fun get(url: String, api: Boolean): Response {
        var target = url
        repeat(Hosts.MAX_REDIRECTS + 1) {
            if (!Hosts.isAllowed(target)) throw IOException("host not allowed")
            val request = Request.Builder().url(target).header("User-Agent", "tutti/${BuildConfig.VERSION_NAME}").apply {
                if (api) {
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                }
            }.build()
            val response = http.newCall(request).execute()
            if (response.isRedirect) {
                val next = Hosts.nextHop(target, response.header("Location"))
                response.close()
                target = next ?: throw IOException("redirect not allowed")
                return@repeat
            }
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
            return response
        }
        throw IOException("too many redirects")
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("tutti.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    // The installer fills in the status extras, so the intent must stay mutable; it is explicit.
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    flags,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun apkFile(): File = File(File(context.cacheDir, "updates"), "tutti-update.apk")

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun updater(): Updater
    }

    companion object {
        private const val PREFS = "updates"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_FOUND = "found_version"
        private const val KEY_SET_ASIDE = "set_aside_version"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        fun from(context: Context): Updater =
            EntryPointAccessors.fromApplication(context.applicationContext, Access::class.java).updater()
    }
}
