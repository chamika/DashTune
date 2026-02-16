package com.chamika.dashtune

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DashTuneApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Disable Firebase collection in debug builds
        if (BuildConfig.DEBUG) {
            Firebase.analytics.setAnalyticsCollectionEnabled(false)
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(false)
        }
    }
}
