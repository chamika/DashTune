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

    // Held as a String: QuickConnect codes can contain leading zeros, which an Int would drop.
    // null signals QuickConnect is unavailable.
    private val _quickConnectCode = MutableLiveData<String?>()
    val quickConnectCode: LiveData<String?> = _quickConnectCode

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
        Log.i(LOG_TAG, "Initiate QuickConnect")
        val api = jellyfin.createApi(serverUrl)

        viewModelScope.launch {
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

                if (response.status == 200) {
                    quickConnectSecret = response.content.secret
                    Log.d(LOG_TAG, "QuickConnect initiated")
                    _quickConnectCode.value = response.content.code

                    do {
                        delay(1.seconds)
                        checkQuickConnect(serverUrl)
                    } while (isActive)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "QuickConnect failed", e)
                FirebaseUtils.safeRecordException(e)
                _quickConnectCode.value = null
            }
        }
    }

    private suspend fun checkQuickConnect(server: String) {
        val api = jellyfin.createApi(server)
        val response = withContext(Dispatchers.IO) {
            api.quickConnectApi.getQuickConnectState(quickConnectSecret)
        }

        Log.d(LOG_TAG, "Checking QuickConnect")

        if (response.status == 200) {
            if (!response.content.authenticated) {
                return
            }

            val loginResponse = withContext(Dispatchers.IO) {
                api.userApi.authenticateWithQuickConnect(QuickConnectDto(response.content.secret))
            }

            if (loginResponse.status == 200) {
                val userName = loginResponse.content.user?.name
                val accessToken = loginResponse.content.accessToken
                if (userName == null || accessToken == null) {
                    Log.w(LOG_TAG, "QuickConnect auth succeeded but response was missing user/token")
                    return
                }
                FirebaseUtils.safeSetCustomKey("auth_method", "quick_connect")
                FirebaseUtils.safeLog("Login successful via quick_connect")
                loginSuccess(server, userName, accessToken)
            }
        }
    }

    suspend fun login(server: String, username: String, password: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(server).userApi.authenticateUserByName(username, password)
            }

            val accessToken = response.content.accessToken
            if (response.status == 200 && accessToken != null) {
                FirebaseUtils.safeSetCustomKey("auth_method", "password")
                FirebaseUtils.safeLog("Login successful via password")
                loginSuccess(server, username, accessToken)
                true
            } else {
                false
            }
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
