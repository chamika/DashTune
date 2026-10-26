package com.chamika.dashtune.auth

import android.accounts.AccountManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Signing in again after the server rejected the token, against the platform AccountManager
 * rather than a mock: the account left behind by invalidateToken() must never shadow the
 * one the user just signed in to.
 */
@RunWith(RobolectricTestRunner::class)
class AccountReSignInTest {

    private val platform = AccountManager.get(ApplicationProvider.getApplicationContext())
    private val accounts = JellyfinAccountManager(platform)

    @Test
    fun `signing in as another user after a 401 leaves the app signed in as them`() {
        accounts.storeAccount("http://server", "alice", "alice-token")
        accounts.invalidateToken()
        assertFalse(accounts.isAuthenticated)

        accounts.storeAccount("http://server", "bob", "bob-token")

        assertTrue(accounts.isAuthenticated)
        assertEquals("bob-token", accounts.token)
        assertEquals(1, platform.getAccountsByType(JellyfinAccountManager.ACCOUNT_TYPE).size)
    }

    @Test
    fun `signing in with the server URL typed differently after a 401 leaves the app signed in`() {
        accounts.storeAccount("http://server", "alice", "old-token")
        accounts.invalidateToken()

        accounts.storeAccount("http://server/", "alice", "new-token")

        assertTrue(accounts.isAuthenticated)
        assertEquals("new-token", accounts.token)
        assertEquals("http://server/", accounts.server)
    }

    @Test
    fun `signing in again as the same user restores the token`() {
        accounts.storeAccount("http://server", "alice", "old-token")
        accounts.invalidateToken()

        accounts.storeAccount("http://server", "alice", "new-token")

        assertTrue(accounts.isAuthenticated)
        assertEquals("new-token", accounts.token)
    }
}
