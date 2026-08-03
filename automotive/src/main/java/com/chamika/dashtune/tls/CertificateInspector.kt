package com.chamika.dashtune.tls

import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.URI
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/** What a server presented, so the user can decide whether to trust it. */
data class ServerCertificate(
    val certificate: X509Certificate,
    val host: String,
    val fingerprintSha256: String,
    val subject: String,
    val issuer: String,
    val notBefore: Date,
    val notAfter: Date,
)

/**
 * Retrieves the certificate a server presents, including one the platform rejects.
 *
 * The recording TrustManager below still delegates to the platform and still throws — it only
 * captures the chain on the way past. The handshake fails exactly as it normally would; we just
 * keep enough detail to show the user a fingerprint before they decide.
 */
@Singleton
class CertificateInspector @Inject constructor() {

    suspend fun inspect(serverUrl: String): ServerCertificate? = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(serverUrl) }.getOrNull() ?: return@withContext null
        val host = uri.host ?: return@withContext null
        if (!uri.scheme.equals("https", ignoreCase = true)) return@withContext null
        val port = if (uri.port != -1) uri.port else DEFAULT_HTTPS_PORT

        val recorder = RecordingTrustManager(PinnedTrustManager.platformTrustManager())
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(recorder), null)
        }

        try {
            (context.socketFactory.createSocket(host, port) as SSLSocket).use { socket ->
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                // Throws when the certificate is untrusted, which is the case we care about.
                // The chain has already been recorded by then.
                runCatching { socket.startHandshake() }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not reach $host:$port to read its certificate", e)
            return@withContext null
        }

        val leaf = recorder.captured?.firstOrNull() ?: return@withContext null
        ServerCertificate(
            certificate = leaf,
            host = host,
            fingerprintSha256 = TrustedCertificateStore.fingerprintOf(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBefore = leaf.notBefore,
            notAfter = leaf.notAfter,
        )
    }

    private class RecordingTrustManager(
        private val delegate: X509ExtendedTrustManager,
    ) : X509ExtendedTrustManager() {

        var captured: Array<out X509Certificate>? = null
            private set

        private fun record(chain: Array<out X509Certificate>) {
            captured = chain
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            record(chain)
            delegate.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            record(chain)
            delegate.checkServerTrusted(chain, authType, socket)
        }

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            record(chain)
            delegate.checkServerTrusted(chain, authType, engine)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            socket: Socket?,
        ) = delegate.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) = delegate.checkClientTrusted(chain, authType, engine)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    private companion object {
        const val DEFAULT_HTTPS_PORT = 443
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}

/** True when [throwable] or anything it wraps is a certificate trust failure. */
fun isCertificateTrustFailure(throwable: Throwable?): Boolean {
    var cause = throwable
    val seen = mutableSetOf<Throwable>()
    while (cause != null && seen.add(cause)) {
        if (cause is CertificateException || cause is javax.net.ssl.SSLHandshakeException) return true
        cause = cause.cause
    }
    return false
}
