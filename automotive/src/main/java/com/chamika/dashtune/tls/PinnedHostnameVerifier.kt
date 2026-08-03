package com.chamika.dashtune.tls

import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession

/**
 * Standard hostname verification, relaxed only for certificates the user pinned.
 *
 * A self-signed certificate often carries a CN that doesn't match the URL the user typed, so
 * pinning the certificate without this would still fail at the hostname check. Approving a
 * certificate for a host is taken as approving that name mismatch for that host alone.
 */
class PinnedHostnameVerifier(
    private val store: TrustedCertificateStore,
    private val delegate: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (delegate.verify(hostname, session)) return true
        val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
        return leaf is X509Certificate && store.isPinned(hostname, leaf)
    }
}
