package com.chamika.dashtune.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AuthenticatorTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var accountManager: AccountManager
    private lateinit var authenticator: Authenticator

    @Before
    fun setUp() {
        accountManager = mockk(relaxed = true)
        mockkStatic(AccountManager::class)
        every { AccountManager.get(context) } returns accountManager
        authenticator = Authenticator(context)
    }

    // --- constants ---

    @Test
    fun `ACCOUNT_TYPE is the app package name`() {
        assertEquals("com.chamika.dashtune", Authenticator.ACCOUNT_TYPE)
    }

    @Test
    fun `AUTHTOKEN_TYPE equals ACCOUNT_TYPE`() {
        assertEquals(Authenticator.ACCOUNT_TYPE, Authenticator.AUTHTOKEN_TYPE)
    }

    // --- editProperties ---

    @Test(expected = UnsupportedOperationException::class)
    fun `editProperties throws UnsupportedOperationException`() {
        authenticator.editProperties(null, Authenticator.ACCOUNT_TYPE)
    }

    // --- addAccount ---

    @Test
    fun `addAccount returns empty Bundle`() {
        val result = authenticator.addAccount(null, Authenticator.ACCOUNT_TYPE, null, null, null)

        assertNotNull(result)
        assertTrue(result.isEmpty)
    }

    // --- confirmCredentials ---

    @Test
    fun `confirmCredentials returns null`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        assertNull(authenticator.confirmCredentials(null, account, null))
    }

    // --- getAuthToken ---

    @Test
    fun `getAuthToken returns error bundle when authTokenType is invalid`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        val result = authenticator.getAuthToken(null, account, "wrong.type", null)

        assertEquals("invalid auth token type", result.getString(AccountManager.KEY_ERROR_MESSAGE))
    }

    @Test
    fun `getAuthToken returns error bundle when account is null`() {
        val result = authenticator.getAuthToken(null, null, Authenticator.AUTHTOKEN_TYPE, null)

        assertEquals("account must not be null", result.getString(AccountManager.KEY_ERROR_MESSAGE))
    }

    @Test
    fun `getAuthToken returns token bundle when password exists`() {
        val account = Account("testuser", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getPassword(account) } returns "stored-token"

        val result = authenticator.getAuthToken(null, account, Authenticator.AUTHTOKEN_TYPE, null)

        assertEquals("testuser", result.getString(AccountManager.KEY_ACCOUNT_NAME))
        assertEquals(Authenticator.ACCOUNT_TYPE, result.getString(AccountManager.KEY_ACCOUNT_TYPE))
        assertEquals("stored-token", result.getString(AccountManager.KEY_AUTHTOKEN))
    }

    @Test
    fun `getAuthToken returns intent bundle when no password stored`() {
        val account = Account("testuser", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getPassword(account) } returns null

        val result = authenticator.getAuthToken(null, account, Authenticator.AUTHTOKEN_TYPE, null)

        assertNotNull(result.getParcelable(AccountManager.KEY_INTENT, android.content.Intent::class.java))
    }

    @Test
    fun `getAuthToken with valid token does not include error message`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        every { accountManager.getPassword(account) } returns "valid-token"

        val result = authenticator.getAuthToken(null, account, Authenticator.AUTHTOKEN_TYPE, null)

        assertNull(result.getString(AccountManager.KEY_ERROR_MESSAGE))
    }

    // --- getAuthTokenLabel ---

    @Test
    fun `getAuthTokenLabel returns null`() {
        assertNull(authenticator.getAuthTokenLabel(Authenticator.AUTHTOKEN_TYPE))
    }

    // --- updateCredentials ---

    @Test
    fun `updateCredentials returns null`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        assertNull(authenticator.updateCredentials(null, account, null, null))
    }

    // --- hasFeatures ---

    @Test
    fun `hasFeatures returns bundle with KEY_BOOLEAN_RESULT false`() {
        val account = Account("user", Authenticator.ACCOUNT_TYPE)
        val result = authenticator.hasFeatures(null, account, emptyArray())

        assertFalse(result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
    }
}
