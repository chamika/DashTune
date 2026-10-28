package com.chamika.dashtune.tls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Plain http carries the Jellyfin token unencrypted, so it is allowed only to local hosts and
 * to hosts the user explicitly accepted at sign-in.
 */
@RunWith(RobolectricTestRunner::class)
class CleartextPolicyTest {

    private lateinit var policy: CleartextPolicy

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("cleartext_hosts", Context.MODE_PRIVATE).edit().clear().commit()
        policy = CleartextPolicy(context)
    }

    @Test
    fun `local network hosts are always allowed`() {
        listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20",
            "169.254.3.4", "100.101.102.103", "nas", "jellyfin.local", "media.lan", "box.home.arpa",
            "server.tailnet-1234.ts.net", "::1", "fe80::1", "fd12:3456::1", "[fd00::2]",
        ).forEach { assertTrue("$it should be local", CleartextPolicy.isLocalHost(it)) }
    }

    @Test
    fun `public hosts and addresses are not`() {
        listOf(
            "jellyfin.example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1",
            "2001:4860:4860::8888", "999.1.1.1",
        ).forEach { assertFalse("$it should not be local", CleartextPolicy.isLocalHost(it)) }
    }

    @Test
    fun `a public http server needs consent until the user accepts it`() {
        assertEquals("jellyfin.example.com", policy.hostNeedingConsent("http://jellyfin.example.com:8096"))

        policy.allow("Jellyfin.Example.com")

        assertNull(policy.hostNeedingConsent("http://jellyfin.example.com:8096"))
        assertTrue(policy.isAllowed("jellyfin.example.com"))
    }

    @Test
    fun `https and local servers never need consent`() {
        assertNull(policy.hostNeedingConsent("https://jellyfin.example.com"))
        assertNull(policy.hostNeedingConsent("http://192.168.1.20:8096"))
        assertNull(policy.hostNeedingConsent("http://nas:8096"))
    }

    @Test
    fun `an existing http sign-in keeps working after the upgrade`() {
        policy.allowExistingServer("http://jellyfin.example.com:8096")

        assertTrue(policy.isAllowed("jellyfin.example.com"))
    }

    @Test
    fun `the upgrade allowance runs once`() {
        policy.allowExistingServer(null)
        policy.allowExistingServer("http://later.example.com")

        assertFalse(policy.isAllowed("later.example.com"))
    }

    @Test
    fun `the guard refuses plain http to a host that was not accepted, before sending`() {
        val chain = chainFor("http://jellyfin.example.com/Users/Me")

        try {
            CleartextGuard(policy).intercept(chain)
            fail("expected the request to be refused")
        } catch (e: CleartextNotAllowedException) {
            // expected
        }
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `the guard lets https, local and accepted hosts through`() {
        policy.allow("accepted.example.com")
        listOf(
            "https://jellyfin.example.com/Users/Me",
            "http://192.168.1.20:8096/Users/Me",
            "http://accepted.example.com/Users/Me",
        ).forEach { url ->
            val chain = chainFor(url)
            CleartextGuard(policy).intercept(chain)
            verify(exactly = 1) { chain.proceed(any()) }
        }
    }

    private fun chainFor(url: String): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        return mockk {
            every { request() } returns request
            every { proceed(any()) } returns mockk(relaxed = true)
        }
    }
}
