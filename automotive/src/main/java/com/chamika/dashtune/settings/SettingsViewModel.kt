package com.chamika.dashtune.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import org.jellyfin.sdk.Jellyfin
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountManager: com.chamika.dashtune.auth.JellyfinAccountManager
) : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    fun versionString(): CharSequence =
        "DashTune: ${jellyfin.clientInfo?.version}, Jellyfin API: ${Jellyfin.apiVersion}"

    fun logout() {
        accountManager.logout()
    }
}
