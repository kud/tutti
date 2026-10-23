package io.kud.tutti.platform.diagnostic

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** "versionName (versionCode)" for reports, read once from the package manager. */
object AppVersion {
    @Volatile private var cached: String? = null

    fun of(context: Context): String = cached ?: read(context).also { cached = it }

    private fun read(context: Context): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")
}
