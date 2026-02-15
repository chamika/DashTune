package com.chamika.dashtune.signin

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamika.dashtune.Constants.LOG_TAG
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

    private val _quickConnectCode = MutableLiveData<Int>()
    val quickConnectCode: LiveData<Int> = _quickConnectCode

    suspend fun pingServer(serverUrl: String): Boolean {
        return try {
            Log.i(LOG_TAG, "Pinging $serverUrl")
            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(serverUrl).systemApi.getPingSystem()
            }
            response.status == 200
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error", e)
            false
        }
    }

    fun startQuickConnect(serverUrl: String) {
        Log.i(LOG_TAG, "Initiate QuickConnect")
        val api = jellyfin.createApi(serverUrl)

        viewModelScope.launch {
            val isEnabled = withContext(Dispatchers.IO) {
                api.quickConnectApi.getQuickConnectEnabled()
            }

            if (isEnabled.status != 200 || !isEnabled.content) {
                _quickConnectCode.value = -1
                return@launch
            }

            val response = withContext(Dispatchers.IO) {
                api.quickConnectApi.initiateQuickConnect()
            }

            if (response.status == 200) {
                quickConnectSecret = response.content.secret
                Log.d(LOG_TAG, "QuickConnect initiated")
                _quickConnectCode.value = Integer.valueOf(response.content.code)

                do {
                    delay(1.seconds)
                    checkQuickConnect(serverUrl)
                } while (isActive)
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
                loginSuccess(
                    server,
                    loginResponse.content.user?.name!!,
                    loginResponse.content.accessToken!!
                )
            }
        }
    }

    suspend fun login(server: String, username: String, password: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(server).userApi.authenticateUserByName(username, password)
            }

            if (response.status == 200) {
                loginSuccess(server, username, response.content.accessToken!!)
            }

            response.status == 200
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error", e)
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
