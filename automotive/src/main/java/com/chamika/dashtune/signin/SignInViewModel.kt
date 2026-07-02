package com.chamika.dashtune.signin

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.FirebaseUtils
import com.chamika.dashtune.auth.JellyfinAccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.QuickConnectDto
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class SignInViewModel @Inject constructor() : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    @Inject
    lateinit var accountManager: JellyfinAccountManager

    private var quickConnectSecret: String = ""

    private val _loggedIn = MutableLiveData<Boolean>()
    val loggedIn: LiveData<Boolean> = _loggedIn

    /** The QuickConnect code to display, or null when QuickConnect is unavailable. */
    private val _quickConnectCode = MutableLiveData<String?>()
    val quickConnectCode: LiveData<String?> = _quickConnectCode

    private var quickConnectJob: kotlinx.coroutines.Job? = null

    suspend fun pingServer(serverUrl: String): Boolean {
        return try {
            Log.i(LOG_TAG, "Pinging $serverUrl")
            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(serverUrl).systemApi.getPingSystem()
            }
            response.status == 200
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error", e)
            val host = try { java.net.URI(serverUrl).host ?: "unknown" } catch (_: Exception) { "invalid_url" }
            FirebaseUtils.safeSetCustomKey("server_url_host", host)
            FirebaseUtils.safeRecordException(e)
            false
        }
    }

    fun startQuickConnect(serverUrl: String) {
        if (quickConnectJob?.isActive == true) return
        Log.i(LOG_TAG, "Initiate QuickConnect")
        val api = jellyfin.createApi(serverUrl)

        quickConnectJob = viewModelScope.launch {
            try {
                val isEnabled = withContext(Dispatchers.IO) {
                    api.quickConnectApi.getQuickConnectEnabled()
                }

                if (isEnabled.status != 200 || !isEnabled.content) {
                    _quickConnectCode.value = null
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    api.quickConnectApi.initiateQuickConnect()
                }

                if (response.status != 200) {
                    _quickConnectCode.value = null
                    return@launch
                }

                quickConnectSecret = response.content.secret
                Log.d(LOG_TAG, "QuickConnect initiated")
                // Keep the code as a string: converting to Int drops leading zeros,
                // showing the user a code the server will never accept.
                _quickConnectCode.value = response.content.code

                do {
                    delay(1.seconds)
                    val authenticated = checkQuickConnect(serverUrl)
                } while (isActive && !authenticated)
            } catch (e: Exception) {
                // Network failure or an expired QuickConnect secret (the server returns an
                // error status once it lapses); surface "unavailable" instead of crashing.
                Log.w(LOG_TAG, "QuickConnect failed", e)
                FirebaseUtils.safeSetCustomKey("auth_method", "quick_connect")
                FirebaseUtils.safeRecordException(e)
                _quickConnectCode.value = null
            }
        }
    }

    /** Returns true once QuickConnect has been approved and login completed. */
    private suspend fun checkQuickConnect(server: String): Boolean {
        val api = jellyfin.createApi(server)
        val response = withContext(Dispatchers.IO) {
            api.quickConnectApi.getQuickConnectState(quickConnectSecret)
        }

        Log.d(LOG_TAG, "Checking QuickConnect")

        if (response.status != 200 || !response.content.authenticated) {
            return false
        }

        val loginResponse = withContext(Dispatchers.IO) {
            api.userApi.authenticateWithQuickConnect(QuickConnectDto(response.content.secret))
        }

        if (loginResponse.status == 200) {
            FirebaseUtils.safeSetCustomKey("auth_method", "quick_connect")
            FirebaseUtils.safeLog("Login successful via quick_connect")
            loginSuccess(
                server,
                loginResponse.content.user?.name!!,
                loginResponse.content.accessToken!!
            )
            return true
        }
        return false
    }

    suspend fun login(server: String, username: String, password: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(server).userApi.authenticateUserByName(username, password)
            }

            if (response.status == 200) {
                FirebaseUtils.safeSetCustomKey("auth_method", "password")
                FirebaseUtils.safeLog("Login successful via password")
                loginSuccess(server, username, response.content.accessToken!!)
            }

            response.status == 200
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error", e)
            FirebaseUtils.safeSetCustomKey("auth_method", "password")
            FirebaseUtils.safeRecordException(e)
            false
        }
    }

    private fun loginSuccess(server: String, username: String, token: String) {
        Log.i(LOG_TAG, "$username successfully authenticated")
        accountManager.storeAccount(server, username, token)
        _loggedIn.postValue(true)
    }

    companion object {
        internal const val JELLYFIN_SERVER_URL = "jellyfinServer"
    }
}
