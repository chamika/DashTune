package com.chamika.dashtune

import android.app.Application
import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DashTuneApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Disable Firebase collection in debug builds.
        // Wrapped in try-catch because Firebase depends on Google Play Services, which may not
        // yet be ready shortly after a car software update (GMS can take several minutes to
        // initialise on first boot after an OTA). Crashing here would prevent the media service
        // from starting and cause the AAOS "Something went wrong" error dialog.
        try {
            if (BuildConfig.DEBUG) {
                Firebase.analytics.setAnalyticsCollectionEnabled(false)
                Firebase.crashlytics.setCrashlyticsCollectionEnabled(false)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Firebase init skipped – Google Play Services not ready yet", e)
        }
    }
}
