package com.chamika.dashtune

import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG

object FirebaseUtils {

    fun safeRecordException(e: Exception) {
        Log.w(LOG_TAG, "Exception recorded: ${e.message}", e)
    }

    fun safeLog(message: String) {
        Log.i(LOG_TAG, message)
    }

    fun safeSetCustomKey(key: String, value: String) {
        Log.d(LOG_TAG, "[$key] $value")
    }

    fun safeSetCustomKey(key: String, value: Int) {
        Log.d(LOG_TAG, "[$key] $value")
    }

    fun safeSetCustomKey(key: String, value: Boolean) {
        Log.d(LOG_TAG, "[$key] $value")
    }
}
