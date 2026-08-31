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
import com.chamika.dashtune.tls.TrustedCertificateStore
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** The `browseCategoryValues` entry for the Downloads category. */
private const val DOWNLOADS_CATEGORY = "downloads"

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var trustedCertificateStore: TrustedCertificateStore

    private lateinit var viewModel: SettingsViewModel
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        findPreference<Preference>("version")?.summary = viewModel.versionString()

        val downloadsStoragePref = findPreference<Preference>("downloads_storage")
        downloadsStoragePref?.let { pref ->
            lifecycleScope.launch {
                pref.summary = getString(R.string.downloads_storage_summary, viewModel.downloadsStorageString())
            }
        }

        findPreference<MultiSelectListPreference>("browse_categories")?.setOnPreferenceChangeListener { pref, newValue ->
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
                else -> {
                    val had = (pref as MultiSelectListPreference).values.contains(DOWNLOADS_CATEGORY)
                    if (selected.contains(DOWNLOADS_CATEGORY) != had) {
                        promptRestartForDownloadsLayout()
                    }
                    true
                }
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

        val clearCachePref = findPreference<Preference>("clear_cache")
        clearCachePref?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.clear_cache_confirmation)
                .setPositiveButton(R.string.clear_cache_confirm) { _, _ ->
                    clearCachePref.isEnabled = false
                    lifecycleScope.launch {
                        viewModel.clearCache()
                        syncPref?.summary = lastSyncSummary()
                        Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                        clearCachePref.isEnabled = true
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        val trustedCertsPref = findPreference<Preference>("trusted_certificates")
        trustedCertsPref?.let { pref ->
            refreshTrustedCertificates(pref)
            pref.setOnPreferenceClickListener {
                showTrustedCertificates(pref)
                true
            }
        }

        findPreference<Preference>("force_exit")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.force_exit_confirmation)
                .setPositiveButton(R.string.force_exit_confirm) { _, _ ->
                    viewModel.forceExit()
                    requireActivity().finishAffinity()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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

    /**
     * Selecting Downloads switches the browse categories from an artwork grid to list items —
     * the only layout the AAOS host draws per-item browse actions in. The host resolves a
     * node's presentation once, when it builds the browse view, and re-delivering children
     * does not revise it, so the new layout only appears after it reconnects. The preference
     * itself is already saved either way; restarting just applies it now instead of whenever
     * DashTune next starts.
     */
    private fun promptRestartForDownloadsLayout() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.downloads_restart_confirmation)
            .setPositiveButton(R.string.downloads_restart_confirm) { _, _ ->
                viewModel.forceExit()
                requireActivity().finishAffinity()
            }
            .setNegativeButton(R.string.downloads_restart_later, null)
            .show()
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

    private fun refreshTrustedCertificates(pref: Preference) {
        val count = trustedCertificateStore.pinnedCertificates().size
        pref.summary = if (count == 0) {
            getString(R.string.trusted_certificates_none)
        } else {
            getString(R.string.trusted_certificates_count, count)
        }
        pref.isEnabled = count > 0
    }

    /** Lists approved certificates so a user can withdraw trust without signing out. */
    private fun showTrustedCertificates(pref: Preference) {
        val pinned = trustedCertificateStore.pinnedCertificates().toList()
        if (pinned.isEmpty()) return

        val labels = pinned.map { (host, fingerprints) ->
            "$host\n${fingerprints.joinToString("\n")}"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.trusted_certificates)
            .setItems(labels) { _, index ->
                val host = pinned[index].first
                AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.trusted_certificate_remove_confirmation, host))
                    .setPositiveButton(R.string.trusted_certificate_remove_confirm) { _, _ ->
                        trustedCertificateStore.remove(host)
                        refreshTrustedCertificates(pref)
                        Toast.makeText(
                            requireContext(),
                            R.string.trusted_certificate_removed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
