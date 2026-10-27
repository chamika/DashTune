package com.chamika.dashtune.tls

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Which hosts may be reached over plain http.
 *
 * Every request carries the Jellyfin token in its Authorization header, and the car roams on
 * cellular and hotspots, so an `http://` server on the internet would hand the token to anyone
 * on the path. A server on the local network is the common, legitimate reason for http, so
 * local hosts are always allowed; any other host only after the user accepted the warning at
 * sign-in. The manifest still permits cleartext: the platform policy cannot tell local hosts
 * apart, so this is where the line is drawn.
 */
@Singleton
class CleartextPolicy @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAllowed(host: String): Boolean = isLocalHost(host) || host.lowercase() in allowedHosts()

    /** The user accepted sending their token unencrypted to [host]. */
    fun allow(host: String) {
        prefs.edit().putStringSet(KEY_HOSTS, allowedHosts() + host.lowercase()).apply()
    }

    fun allowedHosts(): Set<String> = prefs.getStringSet(KEY_HOSTS, emptySet()).orEmpty()

    /**
     * Keeps an existing http sign-in working after the upgrade that introduced this policy:
     * that user chose the server long before there was a warning to accept. Runs once.
     */
    fun allowExistingServer(serverUrl: String?) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val host = serverUrl?.let { runCatching { java.net.URI(it) }.getOrNull() }
            ?.takeIf { it.scheme.equals("http", ignoreCase = true) }
            ?.host
        if (host != null && !isLocalHost(host)) allow(host)
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    /** The host of [serverUrl] if connecting to it needs the user's consent first, else null. */
    fun hostNeedingConsent(serverUrl: String): String? {
        val uri = runCatching { java.net.URI(serverUrl.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        return host.takeUnless { isAllowed(it) }
    }

    companion object {
        private const val PREFS_NAME = "cleartext_hosts"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_MIGRATED = "existing_server_migrated"

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /**
         * Hosts on the local network, or reached through an encrypted tunnel, where http is
         * normal. Literals only: a public name is never looked up here, since DNS can say
         * anything.
         */
        fun isLocalHost(host: String): Boolean {
            val name = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
            if (name == "localhost") return true
            // A single label (http://nas:8096) only resolves on the local network.
            if (!name.contains('.') && !name.contains(':')) return true
            if (LOCAL_SUFFIXES.any { name.endsWith(it) }) return true

            IPV4.matchEntire(name)?.let { match ->
                val o = match.groupValues.drop(1).map { it.toInt() }
                if (o.any { it > 255 }) return false
                return o[0] == 10 ||
                    o[0] == 127 ||
                    (o[0] == 172 && o[1] in 16..31) ||
                    (o[0] == 192 && o[1] == 168) ||
                    (o[0] == 169 && o[1] == 254) ||
                    // Carrier-grade NAT range, which Tailscale uses for its encrypted tunnel.
                    (o[0] == 100 && o[1] in 64..127)
            }

            if (name.contains(':')) {
                val address = runCatching { InetAddress.getByName(name) }.getOrNull() as? Inet6Address
                    ?: return false
                val first = address.address[0].toInt() and 0xFF
                return address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    // Unique local addresses, fc00::/7: IPv6's private range.
                    (first and 0xFE) == 0xFC
            }
            return false
        }

        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net")
    }
}

/** A plain-http request to a host the user never agreed to send their token to unencrypted. */
class CleartextNotAllowedException(host: String) :
    IOException("Unencrypted connection to $host is not allowed")

/** Refuses plain-http requests that [CleartextPolicy] does not allow, before anything is sent. */
class CleartextGuard(private val policy: CleartextPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !policy.isAllowed(url.host)) throw CleartextNotAllowedException(url.host)
        return chain.proceed(chain.request())
    }
}
