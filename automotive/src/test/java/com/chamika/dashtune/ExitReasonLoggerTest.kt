package com.chamika.dashtune

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
class ExitReasonLoggerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the last exit is read back with its reason and description`() {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        shadowOf(activityManager).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(context.packageName)
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setDescription("lmk")
                .setTimestamp(System.currentTimeMillis() - 60_000)
                .build()
        )

        val exit = ExitReasonLogger.readAndLog(context)

        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, exit?.reason)
        assertEquals("LOW_MEMORY", exit?.reasonName)
        assertEquals("lmk", exit?.description)
    }

    @Test
    fun `no record means no exit`() {
        assertNull(ExitReasonLogger.readAndLog(context))
    }

    @Test
    fun `being killed is unexpected`() {
        listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
        ).forEach { assertTrue(ExitReasonLogger.reasonName(it), ExitReasonLogger.isUnexpected(it)) }
    }

    @Test
    fun `stopping on purpose is not unexpected`() {
        listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ).forEach { assertFalse(ExitReasonLogger.reasonName(it), ExitReasonLogger.isUnexpected(it)) }
    }

    @Test
    fun `an unknown reason code is still named`() {
        assertEquals("REASON_99", ExitReasonLogger.reasonName(99))
    }
}
