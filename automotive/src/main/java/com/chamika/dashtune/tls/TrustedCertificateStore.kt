package com.chamika.dashtune.tls

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Certificates the user has explicitly accepted, keyed by host.
 *
 * This is deliberately a pin store rather than a "skip verification" flag. We record the SHA-256
 * fingerprint of the exact leaf certificate the user approved, so a machine-in-the-middle
 * presenting its own certificate still fails: the fingerprint won't match. That matters for a head
 * unit, which roams across cellular and untrusted hotspots carrying a Jellyfin access token.
 *
 * Pins are persisted so playback, prefetch and album art keep working after a restart without
 * re-prompting — the sign-in screen is the only place that can add one.
 */
@Singleton
class TrustedCertificateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Trust [certificate] for [host] from now on. */
    fun pin(host: String, certificate: X509Certificate) {
        val key = host.lowercase()
        val existing = prefs.getStringSet(key, emptySet()).orEmpty()
        prefs.edit()
            .putStringSet(key, existing + fingerprintOf(certificate))
            .apply()
    }

    /**
     * True when the user has approved this exact certificate.
     *
     * [host] is null for the TrustManager overload that carries no peer information; the
     * fingerprint is the real security boundary, so we fall back to matching any pinned host.
     */
    fun isPinned(host: String?, certificate: X509Certificate): Boolean {
        val fingerprint = fingerprintOf(certificate)
        if (host != null) {
            return prefs.getStringSet(host.lowercase(), emptySet()).orEmpty().contains(fingerprint)
        }
        return prefs.all.values.any { it is Set<*> && it.contains(fingerprint) }
    }

    /** Every pinned host mapped to its approved fingerprints, for display in Settings. */
    fun pinnedCertificates(): Map<String, Set<String>> =
        prefs.all.mapNotNull { (host, value) ->
            @Suppress("UNCHECKED_CAST")
            val fingerprints = (value as? Set<String>)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            host to fingerprints
        }.toMap()

    fun remove(host: String) {
        prefs.edit().remove(host.lowercase()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "trusted_certificates"

        /** Colon-separated uppercase SHA-256 of the certificate's DER encoding. */
        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}
