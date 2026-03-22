package com.chamika.dashtune

import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG
import com.google.firebase.crashlytics.FirebaseCrashlytics

object FirebaseUtils {

    /**
     * Records [e] to Firebase Crashlytics, swallowing any exception that might occur if Google
     * Play Services is not yet available (e.g. shortly after a car OTA update).
     */
    fun safeRecordException(e: Exception) {
        try {
            FirebaseCrashlytics.getInstance().recordException(e)
        } catch (crashlyticsError: Exception) {
            Log.w(LOG_TAG, "Crashlytics unavailable – Google Play Services not ready yet", crashlyticsError)
        }
    }

    /** Logs [message] to Firebase Crashlytics, safe against GMS unavailability. */
    fun safeLog(message: String) {
        try {
            FirebaseCrashlytics.getInstance().log(message)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Crashlytics unavailable – Google Play Services not ready yet", e)
        }
    }

    /** Sets a string custom key on Firebase Crashlytics, safe against GMS unavailability. */
    fun safeSetCustomKey(key: String, value: String) {
        try {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Crashlytics unavailable – Google Play Services not ready yet", e)
        }
    }

    /** Sets an int custom key on Firebase Crashlytics, safe against GMS unavailability. */
    fun safeSetCustomKey(key: String, value: Int) {
        try {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Crashlytics unavailable – Google Play Services not ready yet", e)
        }
    }

    /** Sets a boolean custom key on Firebase Crashlytics, safe against GMS unavailability. */
    fun safeSetCustomKey(key: String, value: Boolean) {
        try {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Crashlytics unavailable – Google Play Services not ready yet", e)
        }
    }
}
