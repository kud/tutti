package io.kud.tutti.platform.diagnostic

import android.content.Context
import io.kud.tutti.protocol.net.Redact
import java.io.File

/**
 * Keeps the last crash, scrubbed, so the next launch can show it and it can be copied into a
 * report: there is no other way to see a stack trace on a phone without adb.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(Redact.text("thread: ${thread.name}\n${error.stackTraceToString()}")) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }
}
