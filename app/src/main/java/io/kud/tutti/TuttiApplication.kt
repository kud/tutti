package io.kud.tutti

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.kud.tutti.platform.diagnostic.CrashLog
import io.kud.tutti.platform.sync.SettingsSync
import io.kud.tutti.quick.QuickRuntime
import javax.inject.Inject

@HiltAndroidApp
class TuttiApplication : Application() {
    @Inject lateinit var sync: SettingsSync

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        QuickRuntime.start(this)
        // Settings sync at every process start: catches a change whose debounce the last process
        // didn't outlive, and another phone's change before any screen opens.
        sync.start()
        sync.request()
    }
}
