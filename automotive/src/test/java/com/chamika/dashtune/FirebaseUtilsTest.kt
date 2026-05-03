package com.chamika.dashtune

import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirebaseUtilsTest {

    private lateinit var crashlytics: FirebaseCrashlytics

    @Before
    fun setUp() {
        crashlytics = mockk(relaxed = true)
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
    }

    // --- safeRecordException tests ---

    @Test
    fun `safeRecordException records exception to Crashlytics when available`() {
        val exception = RuntimeException("test error")
        every { crashlytics.recordException(exception) } just runs

        FirebaseUtils.safeRecordException(exception)

        verify { crashlytics.recordException(exception) }
    }

    @Test
    fun `safeRecordException swallows exception when Crashlytics is unavailable`() {
        every { FirebaseCrashlytics.getInstance() } throws RuntimeException("GMS not available")

        FirebaseUtils.safeRecordException(RuntimeException("app error"))
        // No exception should propagate
    }

    @Test
    fun `safeRecordException swallows exception when recordException itself throws`() {
        val exception = RuntimeException("test error")
        every { crashlytics.recordException(any()) } throws IllegalStateException("Crashlytics not initialised")

        FirebaseUtils.safeRecordException(exception)
        // No exception should propagate
    }

    // --- safeLog tests ---

    @Test
    fun `safeLog logs message to Crashlytics when available`() {
        every { crashlytics.log(any()) } just runs

        FirebaseUtils.safeLog("login successful")

        verify { crashlytics.log("login successful") }
    }

    @Test
    fun `safeLog swallows exception when Crashlytics is unavailable`() {
        every { FirebaseCrashlytics.getInstance() } throws RuntimeException("GMS not available")

        FirebaseUtils.safeLog("some message")
        // No exception should propagate
    }

    @Test
    fun `safeLog swallows exception when log itself throws`() {
        every { crashlytics.log(any()) } throws IllegalStateException("Crashlytics not initialised")

        FirebaseUtils.safeLog("some message")
        // No exception should propagate
    }

    // --- safeSetCustomKey(String, String) tests ---

    @Test
    fun `safeSetCustomKey sets string key when Crashlytics is available`() {
        every { crashlytics.setCustomKey(any<String>(), any<String>()) } just runs

        FirebaseUtils.safeSetCustomKey("auth_method", "password")

        verify { crashlytics.setCustomKey("auth_method", "password") }
    }

    @Test
    fun `safeSetCustomKey string overload swallows exception when Crashlytics unavailable`() {
        every { FirebaseCrashlytics.getInstance() } throws RuntimeException("GMS not available")

        FirebaseUtils.safeSetCustomKey("key", "value")
        // No exception should propagate
    }

    @Test
    fun `safeSetCustomKey string overload swallows exception when setCustomKey throws`() {
        every { crashlytics.setCustomKey(any<String>(), any<String>()) } throws RuntimeException("crash")

        FirebaseUtils.safeSetCustomKey("key", "value")
        // No exception should propagate
    }

    // --- safeSetCustomKey(String, Int) tests ---

    @Test
    fun `safeSetCustomKey sets int key when Crashlytics is available`() {
        every { crashlytics.setCustomKey(any<String>(), any<Int>()) } just runs

        FirebaseUtils.safeSetCustomKey("retry_count", 3)

        verify { crashlytics.setCustomKey("retry_count", 3) }
    }

    @Test
    fun `safeSetCustomKey int overload swallows exception when Crashlytics unavailable`() {
        every { FirebaseCrashlytics.getInstance() } throws RuntimeException("GMS not available")

        FirebaseUtils.safeSetCustomKey("key", 42)
        // No exception should propagate
    }

    // --- safeSetCustomKey(String, Boolean) tests ---

    @Test
    fun `safeSetCustomKey sets boolean key when Crashlytics is available`() {
        every { crashlytics.setCustomKey(any<String>(), any<Boolean>()) } just runs

        FirebaseUtils.safeSetCustomKey("is_premium", true)

        verify { crashlytics.setCustomKey("is_premium", true) }
    }

    @Test
    fun `safeSetCustomKey boolean overload swallows exception when Crashlytics unavailable`() {
        every { FirebaseCrashlytics.getInstance() } throws RuntimeException("GMS not available")

        FirebaseUtils.safeSetCustomKey("key", false)
        // No exception should propagate
    }

    @Test
    fun `safeSetCustomKey boolean overload swallows exception when setCustomKey throws`() {
        every { crashlytics.setCustomKey(any<String>(), any<Boolean>()) } throws RuntimeException("crash")

        FirebaseUtils.safeSetCustomKey("key", true)
        // No exception should propagate
    }
}
