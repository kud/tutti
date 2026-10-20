package io.kud.salon

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.kud.salon.quick.QuickRuntime

@HiltAndroidApp
class SalonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QuickRuntime.start(this)
    }
}
