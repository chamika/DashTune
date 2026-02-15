package com.chamika.dashtune.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.chamika.dashtune.signin.SignInActivity

class Authenticator(val context: Context) : AbstractAccountAuthenticator(context) {
    companion object {
        const val ACCOUNT_TYPE = "com.chamika.dashtune"
        const val AUTHTOKEN_TYPE = "com.chamika.dashtune"
    }

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle = throw UnsupportedOperationException()

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        return Bundle()
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        loginOptions: Bundle?
    ): Bundle {
        if (authTokenType != AUTHTOKEN_TYPE) {
            val res = Bundle()
            res.putString(AccountManager.KEY_ERROR_MESSAGE, "invalid auth token type")
            return res
        }

        if (account == null) {
            val res = Bundle()
            res.putString(AccountManager.KEY_ERROR_MESSAGE, "account must not be null")
            return res
        }

        val accountManager = AccountManager.get(context)
        val password = accountManager.getPassword(account)
        if (password != null) {
            val res = Bundle()
            res.putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            res.putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE)
            res.putString(AccountManager.KEY_AUTHTOKEN, password)
            return res
        }

        val intent = Intent(context, SignInActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        val bundle = Bundle()
        bundle.putParcelable(AccountManager.KEY_INTENT, intent)
        return bundle
    }

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle {
        val result = Bundle()
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
}
