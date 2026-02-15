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
        var account = accountManager.getAccountsByType(ACCOUNT_TYPE).firstOrNull {
            accountManager.getUserData(it, USERDATA_SERVER_KEY).equals(server) &&
                    it.name.equals(username)
        }

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
}
