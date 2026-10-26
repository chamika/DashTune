package com.chamika.dashtune

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG

/**
 * Records why the previous process ended. The service has been recreated mid-drive with no
 * crash, no ANR and no onDestroy — music stopped and nothing in Crashlytics said why — so
 * every start now leaves the system's own exit record as a breadcrumb and custom keys.
 */
object ExitReasonLogger {

    data class LastExit(val reason: Int, val reasonName: String, val description: String?, val timestamp: Long)

    /** Reads and records the last exit. Null below API 30, or when the system kept no record. */
    fun readAndLog(context: Context): LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val info = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                ?.firstOrNull()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read the previous exit reason", e)
            null
        } ?: return null

        val exit = LastExit(info.reason, reasonName(info.reason), info.description, info.timestamp)
        val ageSeconds = (System.currentTimeMillis() - info.timestamp) / 1000
        Log.i(LOG_TAG, "Previous exit: ${exit.reasonName} ${ageSeconds}s ago (${exit.description})")
        FirebaseUtils.safeSetCustomKey("last_exit_reason", exit.reasonName)
        FirebaseUtils.safeSetCustomKey("last_exit_description", exit.description ?: "")
        FirebaseUtils.safeLog(
            "Previous exit: ${exit.reasonName} status=${info.status} " +
                "importance=${info.importance} ${ageSeconds}s ago (${exit.description})"
        )
        return exit
    }

    /**
     * True when the process ended without anyone asking it to: killed for memory, by a signal,
     * an ANR or a crash, or for a reason the system did not give. False for a force stop, our
     * own Force exit (exitProcess), an app update and the like.
     */
    fun isUnexpected(reason: Int): Boolean = reason !in intentionalReasons

    private val intentionalReasons = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
    )

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }
}
