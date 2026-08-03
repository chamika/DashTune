package com.chamika.dashtune.signin

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.chamika.dashtune.R
import com.chamika.dashtune.signin.SignInViewModel.Companion.JELLYFIN_SERVER_URL
import com.chamika.dashtune.tls.ServerCertificate
import kotlinx.coroutines.launch
import java.text.DateFormat

class ServerSignInFragment : Fragment() {

    private lateinit var serverInput: EditText
    private lateinit var submitServer: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var viewModel: SignInViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_server, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[SignInViewModel::class.java]

        serverInput = view.findViewById(R.id.server_uri)
        submitServer = view.findViewById(R.id.submit_server_button)
        progressBar = view.findViewById(R.id.progress_bar)
        errorText = view.findViewById(R.id.error_text)

        submitServer.setOnClickListener {
            val serverUrl = serverInput.text
            if (!TextUtils.isEmpty(serverUrl)) {
                connect(serverUrl)
            }
        }
    }

    private fun connect(serverUrl: Editable) {
        progressBar.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = viewModel.pingServer(serverUrl.toString())) {
                is PingResult.Success -> signInToServer(serverUrl)

                is PingResult.UntrustedCertificate -> {
                    progressBar.visibility = View.INVISIBLE
                    promptToTrust(result.certificate, serverUrl)
                }

                is PingResult.Unreachable -> {
                    progressBar.visibility = View.INVISIBLE
                    errorText.setText(R.string.server_unreachable)
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Show what the server presented and let the user decide. The fingerprint is the part worth
     * checking against the server, so it gets its own line rather than being buried in the subject.
     */
    private fun promptToTrust(certificate: ServerCertificate, serverUrl: Editable) {
        val expiry = DateFormat.getDateInstance(DateFormat.MEDIUM).format(certificate.notAfter)
        val details = getString(
            R.string.untrusted_certificate_details,
            certificate.host,
            certificate.issuer,
            expiry,
            certificate.fingerprintSha256,
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.untrusted_certificate_title)
            .setMessage(details)
            .setPositiveButton(R.string.untrusted_certificate_trust) { _, _ ->
                viewModel.trustCertificate(certificate)
                connect(serverUrl)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                errorText.setText(R.string.untrusted_certificate_rejected)
                errorText.visibility = View.VISIBLE
            }
            .show()
    }

    private fun signInToServer(serverUrl: Editable) {
        val args = Bundle()
        args.putString(JELLYFIN_SERVER_URL, serverUrl.toString())
        val fragment = CredentialsFragment()
        fragment.arguments = args

        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.sign_in_container, fragment)
            .addToBackStack("landingPage")
            .commit()
    }
}
