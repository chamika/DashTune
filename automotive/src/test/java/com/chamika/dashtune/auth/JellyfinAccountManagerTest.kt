package com.chamika.dashtune.auth

import android.accounts.Account
import android.accounts.AccountManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JellyfinAccountManagerTest {

    private lateinit var accountManager: AccountManager
    private lateinit var jellyfinAccountManager: JellyfinAccountManager

    @Before
    fun setUp() {
        accountManager = mockk(relaxed = true)
        jellyfinAccountManager = JellyfinAccountManager(accountManager)
    }

    // --- isAuthenticated tests ---

    @Test
    fun `isAuthenticated returns false when no accounts exist`() {
        every { accountManager.getAccountsByType(any()) } returns emptyArray()

        assertFalse(jellyfinAccountManager.isAuthenticated)
    }

    @Test
    fun `isAuthenticated returns false when account exists but no token`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(account)
        every { accountManager.peekAuthToken(account, any()) } returns null

        assertFalse(jellyfinAccountManager.isAuthenticated)
    }

    @Test
    fun `isAuthenticated returns true when account exists with token`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(account)
        every { accountManager.peekAuthToken(account, JellyfinAccountManager.TOKEN_TYPE) } returns "test-token"

        assertTrue(jellyfinAccountManager.isAuthenticated)
    }

    // --- server tests ---

    @Test
    fun `server returns null when no accounts exist`() {
        every { accountManager.getAccountsByType(any()) } returns emptyArray()

        assertNull(jellyfinAccountManager.server)
    }

    @Test
    fun `server returns server URL from account user data`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(account)
        every { accountManager.getUserData(account, JellyfinAccountManager.USERDATA_SERVER_KEY) } returns "http://jellyfin.local:8096"

        assertEquals("http://jellyfin.local:8096", jellyfinAccountManager.server)
    }

    // --- token tests ---

    @Test
    fun `token returns null when no accounts exist`() {
        every { accountManager.getAccountsByType(any()) } returns emptyArray()

        assertNull(jellyfinAccountManager.token)
    }

    @Test
    fun `token returns auth token from account`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(account)
        every { accountManager.peekAuthToken(account, JellyfinAccountManager.TOKEN_TYPE) } returns "my-token"

        assertEquals("my-token", jellyfinAccountManager.token)
    }

    // --- storeAccount tests ---

    @Test
    fun `storeAccount creates new account when none exists`() {
        every { accountManager.getAccountsByType(any()) } returns emptyArray()
        every { accountManager.addAccountExplicitly(any(), any(), any()) } returns true

        val result = jellyfinAccountManager.storeAccount(
            "http://server.local",
            "testuser",
            "access-token"
        )

        assertEquals("testuser", result.name)
        assertEquals(Authenticator.ACCOUNT_TYPE, result.type)
        verify { accountManager.addAccountExplicitly(any(), eq(""), any()) }
        verify { accountManager.setAuthToken(any(), eq(JellyfinAccountManager.TOKEN_TYPE), eq("access-token")) }
    }

    @Test
    fun `storeAccount reuses existing account with matching server and username`() {
        val existingAccount = Account("testuser", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(existingAccount)
        every { accountManager.getUserData(existingAccount, JellyfinAccountManager.USERDATA_SERVER_KEY) } returns "http://server.local"

        val result = jellyfinAccountManager.storeAccount(
            "http://server.local",
            "testuser",
            "new-token"
        )

        assertEquals("testuser", result.name)
        verify(exactly = 0) { accountManager.addAccountExplicitly(any(), any(), any()) }
        verify { accountManager.setAuthToken(existingAccount, JellyfinAccountManager.TOKEN_TYPE, "new-token") }
    }

    @Test
    fun `storeAccount creates new account when existing account has different server`() {
        val existingAccount = Account("testuser", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(existingAccount)
        every { accountManager.getUserData(existingAccount, JellyfinAccountManager.USERDATA_SERVER_KEY) } returns "http://other-server.local"
        every { accountManager.addAccountExplicitly(any(), any(), any()) } returns true

        val result = jellyfinAccountManager.storeAccount(
            "http://new-server.local",
            "testuser",
            "access-token"
        )

        assertNotNull(result)
        verify { accountManager.addAccountExplicitly(any(), eq(""), any()) }
    }

    @Test
    fun `storeAccount creates new account when existing account has different username`() {
        val existingAccount = Account("otheruser", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(existingAccount)
        every { accountManager.getUserData(existingAccount, JellyfinAccountManager.USERDATA_SERVER_KEY) } returns "http://server.local"
        every { accountManager.addAccountExplicitly(any(), any(), any()) } returns true

        val result = jellyfinAccountManager.storeAccount(
            "http://server.local",
            "newuser",
            "access-token"
        )

        assertEquals("newuser", result.name)
        verify { accountManager.addAccountExplicitly(any(), eq(""), any()) }
    }

    // --- logout tests ---

    @Test
    fun `logout removes account when one exists`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(account)

        jellyfinAccountManager.logout()

        verify { accountManager.removeAccountExplicitly(account) }
    }

    @Test
    fun `logout does nothing when no account exists`() {
        every { accountManager.getAccountsByType(any()) } returns emptyArray()

        jellyfinAccountManager.logout()

        verify(exactly = 0) { accountManager.removeAccountExplicitly(any()) }
    }

    // --- multiple accounts tests ---

    @Test
    fun `uses first account from account list`() {
        val account1 = Account("user1", Authenticator.ACCOUNT_TYPE)
        val account2 = Account("user2", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getAccountsByType(any()) } returns arrayOf(account1, account2)
        every { accountManager.peekAuthToken(account1, JellyfinAccountManager.TOKEN_TYPE) } returns "token-1"

        assertEquals("token-1", jellyfinAccountManager.token)
    }

    // --- companion constants tests ---

    @Test
    fun `ACCOUNT_TYPE matches Authenticator ACCOUNT_TYPE`() {
        assertEquals(Authenticator.ACCOUNT_TYPE, JellyfinAccountManager.ACCOUNT_TYPE)
    }

    @Test
    fun `TOKEN_TYPE is derived from ACCOUNT_TYPE`() {
        assertTrue(JellyfinAccountManager.TOKEN_TYPE.startsWith(JellyfinAccountManager.ACCOUNT_TYPE))
        assertTrue(JellyfinAccountManager.TOKEN_TYPE.endsWith("access_token"))
    }

    @Test
    fun `USERDATA_SERVER_KEY is derived from ACCOUNT_TYPE`() {
        assertTrue(JellyfinAccountManager.USERDATA_SERVER_KEY.startsWith(JellyfinAccountManager.ACCOUNT_TYPE))
        assertTrue(JellyfinAccountManager.USERDATA_SERVER_KEY.endsWith("server"))
    }
}
