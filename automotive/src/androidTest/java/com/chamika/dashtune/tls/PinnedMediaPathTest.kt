package com.chamika.dashtune.tls

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chamika.dashtune.di.DashTuneModule
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.SSLHandshakeException

/**
 * Exercises the media data source — the path playback, buffering and prefetch use — against a
 * server whose certificate Android rejects.
 *
 * Builds the client with the real production provider and wraps it exactly as
 * DashTuneMusicService does, so this proves the wiring rather than a copy of it.
 *
 * The untrusted server is a local one holding a self-signed certificate, rather than a real
 * host on the internet: the assertions are about DashTune's pinning, and a test that needs
 * a particular third-party server to be reachable fails for reasons that have nothing to do
 * with this app.
 */
@RunWith(AndroidJUnit4::class)
class PinnedMediaPathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = TrustedCertificateStore(context)

    private lateinit var server: MockWebServer
    private lateinit var url: String

    @Before
    fun startUntrustedServer() {
        store.clear()

        // Self-signed, so the platform trust manager rejects it exactly as it would reject
        // the self-hosted Jellyfin instances this pinning flow exists for.
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().code(200).body("ok").build()
        }
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        url = server.url("/System/Info/Public").toString()
    }

    @After
    fun tearDown() {
        store.clear()
        server.close()
    }

    private fun openMediaDataSource(): Long {
        val client = DashTuneModule().provideOkHttpClient(store)
        val dataSource = OkHttpDataSource.Factory(client).createDataSource()
        return dataSource.open(DataSpec(Uri.parse(url)))
    }

    @Test
    fun untrustedCertificateFailsTheMediaPath() {
        try {
            openMediaDataSource()
            throw AssertionError("Expected the media data source to reject an untrusted certificate")
        } catch (e: Exception) {
            assertTrue(
                "Expected a TLS failure but got: $e",
                generateSequence(e as Throwable) { it.cause }.any { it is SSLHandshakeException }
            )
        }
    }

    @Test
    fun pinnedCertificateAllowsTheMediaPath() {
        val certificate = runBlocking { CertificateInspector().inspect(url) }
        assertNotNull("Could not read the server certificate", certificate)
        store.pin(certificate!!.host, certificate.certificate)

        // Throws if the handshake fails; returning means playback/prefetch can stream from here.
        openMediaDataSource()
    }
}
