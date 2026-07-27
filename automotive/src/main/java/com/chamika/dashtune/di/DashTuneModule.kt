package com.chamika.dashtune.di

import android.accounts.AccountManager
import android.content.Context
import com.chamika.dashtune.R
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.tls.PinnedHostnameVerifier
import com.chamika.dashtune.tls.PinnedTrustManager
import com.chamika.dashtune.tls.TrustedCertificateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import javax.inject.Singleton
import javax.net.ssl.SSLContext

@Module
@InstallIn(SingletonComponent::class)
class DashTuneModule {

    /**
     * The single OkHttp client every network path shares — Jellyfin API calls, album art, playback
     * and prefetch. Sharing it is what makes a certificate the user approves at sign-in apply to
     * streaming and buffering too, rather than only to the login request.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(store: TrustedCertificateStore): OkHttpClient {
        val trustManager = PinnedTrustManager(PinnedTrustManager.platformTrustManager(), store)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(PinnedHostnameVerifier(store))
            .build()
    }

    @Provides
    fun provideJellyfin(
        @ApplicationContext appContext: Context,
        okHttpClient: OkHttpClient,
    ): Jellyfin {
        val version =
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName

        return createJellyfin {
            clientInfo = ClientInfo(appContext.getString(R.string.app_name), version ?: "unknown")
            deviceInfo = androidDevice(appContext)
            context = appContext
            apiClientFactory = OkHttpFactory(base = okHttpClient)
        }
    }

    @Provides
    fun provideAccountManager(@ApplicationContext appContext: Context): JellyfinAccountManager {
        return JellyfinAccountManager(AccountManager.get(appContext))
    }
}
