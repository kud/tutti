package io.kud.tutti

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.kud.tutti.quick.QuickRuntime

@HiltAndroidApp
class TuttiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickRuntime.start(this)
    }
}
