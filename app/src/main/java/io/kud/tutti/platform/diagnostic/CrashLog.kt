package io.kud.tutti.platform.diagnostic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.kud.tutti.protocol.net.Redact
import java.io.File

/**
 * Keeps the last crash so the next launch can show it and it can be copied into a report:
 * there is no other way to see a stack trace on a phone without adb. A Java crash is written
 * raw by the uncaught-exception handler; a death the handler never sees (native crash, ANR,
 * killed by a signal) comes from the system's record of how the process last exited. Either
 * is scrubbed only when read, so the handler does as little as possible while dying.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText("thread: ${thread.name}\n${error.stackTraceToString()}") }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val raw = file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            ?: lastAbnormalExit(context)
            ?: return null
        return runCatching { Redact.text(raw) }.getOrDefault(raw.take(MAX_CHARS))
    }

    fun clear(context: Context) {
        file(context).delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lastExit(context)?.let { prefs(context).edit().putLong(SEEN_KEY, it.timestamp).apply() }
        }
    }

    private fun lastAbnormalExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exit = lastExit(context) ?: return null
        if (exit.timestamp <= prefs(context).getLong(SEEN_KEY, 0L)) return null
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${exit.status}"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
            else -> return null
        }
        // An ANR carries the threads' stacks as text; a native crash's trace is a binary tombstone.
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText().take(MAX_CHARS) } }.getOrNull()
        } else {
            null
        }
        return buildString {
            appendLine("exit: $reason")
            appendLine("process: ${exit.processName}, importance ${exit.importance}")
            exit.description?.let { appendLine("description: $it") }
            trace?.let { appendLine(); append(it) }
        }
    }

    private fun lastExit(context: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)

    private const val SEEN_KEY = "seen_exit"
    private const val MAX_CHARS = 20_000
}
