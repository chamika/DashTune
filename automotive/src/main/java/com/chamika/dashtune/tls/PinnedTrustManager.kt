package com.chamika.dashtune.tls

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Platform certificate validation, with a fallback to certificates the user explicitly approved.
 *
 * Every check is delegated to the system TrustManager first, so normal certificates validate
 * normally. Only when the platform rejects one do we consult [store] — and only an exact
 * fingerprint match the user approved is accepted. Anything else rethrows, so this never degrades
 * into blanket "accept any certificate".
 */
class PinnedTrustManager(
    private val delegate: X509ExtendedTrustManager,
    private val store: TrustedCertificateStore,
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        acceptOrRethrow(chain, host = null) { delegate.checkServerTrusted(chain, authType) }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket?,
    ) {
        acceptOrRethrow(chain, hostOf(socket)) {
            delegate.checkServerTrusted(chain, authType, socket)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) {
        acceptOrRethrow(chain, engine?.peerHost) {
            delegate.checkServerTrusted(chain, authType, engine)
        }
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

    private inline fun acceptOrRethrow(
        chain: Array<out X509Certificate>,
        host: String?,
        validate: () -> Unit,
    ) {
        try {
            validate()
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            if (!store.isPinned(host, leaf)) throw e
        }
    }

    /**
     * The peer hostname as the socket knows it. During the handshake OkHttp has already supplied
     * it via the SNI-carrying socket, so [SSLSocket.getHandshakeSession] is the reliable source —
     * `inetAddress.hostName` would trigger a reverse DNS lookup and can return the IP instead.
     */
    private fun hostOf(socket: Socket?): String? =
        (socket as? SSLSocket)?.handshakeSession?.peerHost

    companion object {
        /** The system default TrustManager — the one that consults Android's CA store. */
        fun platformTrustManager(): X509ExtendedTrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers
                .filterIsInstance<X509ExtendedTrustManager>()
                .firstOrNull()
                ?: error("No X509ExtendedTrustManager available from the platform")
        }
    }
}
