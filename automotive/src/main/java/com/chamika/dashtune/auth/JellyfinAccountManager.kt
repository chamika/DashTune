package com.chamika.dashtune.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle

class JellyfinAccountManager(private val accountManager: AccountManager) {

    companion object {
        const val ACCOUNT_TYPE = Authenticator.ACCOUNT_TYPE
        const val TOKEN_TYPE = "$ACCOUNT_TYPE.access_token"
        const val USERDATA_SERVER_KEY = "$ACCOUNT_TYPE.server"
    }

    private val account: Account?
        get() = accountManager.getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    val server: String?
        get() = account?.let { accountManager.getUserData(it, USERDATA_SERVER_KEY) }

    val token: String?
        get() = account?.let { accountManager.peekAuthToken(it, TOKEN_TYPE) }

    val isAuthenticated: Boolean
        get() = token != null

    fun storeAccount(server: String, username: String, token: String): Account {
        val existing = accountManager.getAccountsByType(ACCOUNT_TYPE)
        var account = existing.firstOrNull {
            // getUserData can return null; calling .equals() on it directly would NPE
            accountManager.getUserData(it, USERDATA_SERVER_KEY) == server &&
                    it.name == username
        }

        // The app holds one account, and every getter reads the first one. Any other — kept
        // by invalidateToken() after a 401, then a sign-in as another user or with the URL
        // typed differently — could come first and leave the app signed out after a
        // successful sign-in.
        existing.filter { it != account }.forEach { accountManager.removeAccountExplicitly(it) }

        if (account == null) {
            account = Account(username, ACCOUNT_TYPE)
            accountManager.addAccountExplicitly(
                account,
                "",
                Bundle().also { it.putString(USERDATA_SERVER_KEY, server) }
            )
        }

        accountManager.setAuthToken(account, TOKEN_TYPE, token)

        return account
    }

    /**
     * Forgets a token the server no longer accepts. The account stays, so [isAuthenticated]
     * turns false and signing in again stores the new token on the same account.
     */
    fun invalidateToken() {
        token?.let { accountManager.invalidateAuthToken(ACCOUNT_TYPE, it) }
    }

    fun logout() {
        account?.let { accountManager.removeAccountExplicitly(it) }
    }
}
