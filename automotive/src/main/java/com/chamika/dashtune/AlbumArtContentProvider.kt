package com.chamika.dashtune

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.media.roundCorners
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlbumArtContentProvider : ContentProvider() {

    /**
     * Hilt can't inject a ContentProvider (providers are created before the Application is fully
     * initialised), so the shared client is pulled from the entry point instead. It's resolved
     * lazily on the first artwork request — long after startup — and reuses the app-wide TLS
     * configuration so pinned certificates apply to album art too.
     */
    private val client: OkHttpClient by lazy {
        EntryPointAccessors
            .fromApplication(context!!.applicationContext, AlbumArtEntryPoint::class.java)
            .okHttpClient()
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlbumArtEntryPoint {
        fun okHttpClient(): OkHttpClient
    }

    companion object {
        // Written from the media session/browse threads and read from binder threads,
        // so it must be a concurrent map.
        private val uriMap = java.util.concurrent.ConcurrentHashMap<Uri, Uri>()
        private val inProgress = HashMap<Uri, CountDownLatch>()
        private val PLACEHOLDER_LOCK = Any()
        private const val PLACEHOLDER_SIZE_PX = 512

        fun mapUri(uri: Uri): Uri {
            val path = uri.encodedPath?.substring(1)?.replace('/', ':') ?: return Uri.EMPTY
            val contentUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("com.chamika.dashtune")
                .path(path)
                .build()
            uriMap[contentUri] = uri
            return contentUri
        }

        fun originalUri(contentUri: Uri): Uri? = uriMap[contentUri]

        /**
         * Where the processed artwork for [contentUri] is cached. The suffix versions the
         * processing: files written before corners were rounded keep their old name and are
         * never served again. Bump it whenever the processing changes.
         */
        private fun cacheFile(cacheDir: File, contentUri: Uri): File? {
            val path = contentUri.path?.removePrefix("/") ?: return null
            return File(cacheDir, "$path.r1")
        }

        fun clearCache(cacheDir: File) {
            uriMap.keys.forEach { contentUri ->
                cacheFile(cacheDir, contentUri)?.delete()
            }
            uriMap.clear()
            // Also remove any lingering files from previous sessions not in uriMap
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("Items")) file.delete()
            }
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = this.context ?: return null
        val remoteUri = uriMap[uri] ?: throw FileNotFoundException(uri.path)
        val file = cacheFile(context.cacheDir, uri) ?: throw FileNotFoundException("null path")

        if (file.exists()) {
            Log.d(LOG_TAG, "Returning existing file for $remoteUri: $file")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val existingLatch = synchronized(inProgress) {
            if (inProgress.contains(remoteUri)) {
                inProgress[remoteUri]
            } else {
                inProgress[remoteUri] = CountDownLatch(1)
                null
            }
        }

        if (existingLatch != null) {
            Log.d(LOG_TAG, "Waiting for image download in separate thread... $remoteUri")
            existingLatch.await(15, TimeUnit.SECONDS)
            Log.d(LOG_TAG, "... Available!")
            return openOrPlaceholder(file)
        }

        val tmpFile = File.createTempFile("dashtune-albumart", ".png", context.cacheDir)
        val request: Request = Request.Builder()
            .url(remoteUri.toString())
            .build()

        Log.d(LOG_TAG, "Downloading $remoteUri ...")
        try {
            client.newCall(request).execute().use {
                if (it.code == 200) {
                    Log.d(LOG_TAG, "Downloaded $remoteUri")
                    val source = it.body.source()
                    source.request(Long.MAX_VALUE)

                    val sink = tmpFile.sink().buffer()
                    sink.writeAll(source)
                    sink.flush()
                    sink.close()

                    roundInPlace(tmpFile)
                    tmpFile.renameTo(file)
                } else {
                    Log.w(LOG_TAG, "Failed to download $remoteUri: \n ${it.code} - ${it.body}")
                    FirebaseUtils.safeSetCustomKey("album_art_path", remoteUri.path ?: "unknown")
                    FirebaseUtils.safeRecordException(Exception("Album art download failed: HTTP ${it.code}"))
                }
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Network error downloading $remoteUri", e)
        } finally {
            tmpFile.delete()
            synchronized(inProgress) {
                inProgress[remoteUri]?.countDown()
                inProgress.remove(remoteUri)
            }
        }

        return openOrPlaceholder(file)
    }

    /**
     * Opens [file], or the placeholder tile when there is no artwork to serve (no Primary image
     * on the server, or offline with nothing cached). Throwing here would make the host draw its
     * own placeholder, a square in a random flat colour.
     *
     * The placeholder is never cached under the item's name, so artwork added on the server
     * later still shows up.
     */
    private fun openOrPlaceholder(file: File): ParcelFileDescriptor {
        val served = if (file.exists()) file else placeholderFile()
        return ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun placeholderFile(): File {
        val context = context!!
        // Not prefixed "Items", so clearCache leaves it alone: it never goes stale.
        val file = File(context.cacheDir, "placeholder.r1.png")
        synchronized(PLACEHOLDER_LOCK) {
            if (!file.exists()) {
                val drawable = context.getDrawable(R.drawable.art_placeholder)
                    ?: throw FileNotFoundException("art_placeholder")
                val bitmap = Bitmap.createBitmap(
                    PLACEHOLDER_SIZE_PX, PLACEHOLDER_SIZE_PX, Bitmap.Config.ARGB_8888
                )
                drawable.setBounds(0, 0, PLACEHOLDER_SIZE_PX, PLACEHOLDER_SIZE_PX)
                drawable.draw(Canvas(bitmap))
                val tmp = File.createTempFile("dashtune-placeholder", ".png", context.cacheDir)
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                tmp.renameTo(file)
            }
        }
        return file
    }

    /**
     * Rewrites [file] as a PNG with rounded corners. Anything that fails to decode is left
     * as downloaded: square artwork beats no artwork.
     */
    private fun roundInPlace(file: File) {
        val decoded = BitmapFactory.decodeFile(file.path) ?: run {
            Log.w(LOG_TAG, "Could not decode artwork, serving it unrounded: $file")
            return
        }
        val rounded = roundCorners(decoded)
        decoded.recycle()
        file.outputStream().use { rounded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rounded.recycle()
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun getType(uri: Uri): String? = null
}
