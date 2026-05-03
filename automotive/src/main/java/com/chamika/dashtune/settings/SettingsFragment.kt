package com.chamika.dashtune.settings

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.chamika.dashtune.DashTuneMusicService
import com.chamika.dashtune.DashTuneSessionCallback.Companion.SYNC_COMMAND
import com.chamika.dashtune.R
import com.chamika.dashtune.signin.SignInActivity
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var viewModel: SettingsViewModel
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        findPreference<Preference>("version")?.summary = viewModel.versionString()

        findPreference<MultiSelectListPreference>("browse_categories")?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selected = newValue as? Set<String> ?: return@setOnPreferenceChangeListener false
            when {
                selected.size < 2 -> {
                    Toast.makeText(requireContext(), R.string.min_categories_warning, Toast.LENGTH_SHORT).show()
                    false
                }
                selected.size > 4 -> {
                    Toast.makeText(requireContext(), R.string.max_categories_warning, Toast.LENGTH_SHORT).show()
                    false
                }
                else -> true
            }
        }

        val syncPref = findPreference<Preference>("sync_library")
        syncPref?.summary = lastSyncSummary()
        syncPref?.setOnPreferenceClickListener {
            syncPref.isEnabled = false
            val controller = if (controllerFuture.isDone) controllerFuture.get() else null
            if (controller == null) {
                syncPref.isEnabled = true
                return@setOnPreferenceClickListener true
            }
            val resultFuture = controller.sendCustomCommand(
                SessionCommand(SYNC_COMMAND, Bundle.EMPTY), Bundle.EMPTY
            )
            resultFuture.addListener({
                val result = resultFuture.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    syncPref.summary = lastSyncSummary()
                }
                syncPref.isEnabled = true
            }, requireActivity().mainExecutor)
            true
        }

        findPreference<Preference>("sign_out")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.sign_out_confirmation)
                .setPositiveButton(R.string.sign_out_confirm) { _, _ ->
                    lifecycleScope.launch {
                        viewModel.logout()
                        val intent = Intent(requireContext(), SignInActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(
            requireContext(),
            ComponentName(requireContext(), DashTuneMusicService::class.java)
        )
        controllerFuture = MediaController.Builder(requireContext(), token).buildAsync()
    }

    override fun onStop() {
        MediaController.releaseFuture(controllerFuture)
        super.onStop()
    }

    private fun lastSyncSummary(): String {
        val lastSync = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getLong("last_sync_timestamp", 0L)
        return if (lastSync > 0) {
            val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(lastSync))
            getString(R.string.sync_library_last_synced, formatted)
        } else {
            getString(R.string.sync_library_never)
        }
    }
}
