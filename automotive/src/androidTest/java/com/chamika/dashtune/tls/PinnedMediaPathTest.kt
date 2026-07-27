package com.chamika.dashtune.tls

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chamika.dashtune.di.DashTuneModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 */
@RunWith(AndroidJUnit4::class)
class PinnedMediaPathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = TrustedCertificateStore(context)

    // Unauthenticated Jellyfin endpoint, so this needs no credentials.
    private val url = "https://diotify.dedyn.io:4433/System/Info/Public"

    @Before
    fun clearPins() = store.clear()

    @After
    fun tearDown() = store.clear()

    private fun openMediaDataSource(): Long {
        val client = DashTuneModule().provideOkHttpClient(store)
        val dataSource = OkHttpDataSource.Factory(client).createDataSource()
        return dataSource.open(DataSpec(Uri.parse(url)))
    }

    @Test
    fun untrustedCertificateFailsTheMediaPath() {
        try {
            openMediaDataSource()
            fail("Expected the media data source to reject an untrusted certificate")
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
