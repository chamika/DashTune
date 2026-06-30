package com.chamika.dashtune

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.chamika.dashtune.Constants.LOG_TAG
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlbumArtContentProvider : ContentProvider() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private val uriMap = ConcurrentHashMap<Uri, Uri>()
        private val inProgress = HashMap<Uri, CountDownLatch>()

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

        fun clearCache(cacheDir: File) {
            uriMap.keys.forEach { contentUri ->
                val path = contentUri.path?.removePrefix("/") ?: return@forEach
                File(cacheDir, path).delete()
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
        val path = uri.path?.removePrefix("/") ?: throw FileNotFoundException("null path")
        val file = File(context.cacheDir, path)

        // Defense in depth: ensure the resolved file stays within cacheDir even if a crafted
        // path slips past the uriMap gate.
        val cacheRoot = context.cacheDir.canonicalFile
        if (!file.canonicalFile.toPath().startsWith(cacheRoot.toPath())) {
            throw FileNotFoundException("Invalid path: ${uri.path}")
        }

        if (file.exists()) {
            Log.d(LOG_TAG, "Returning existing album art file: ${file.name}")
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
            Log.d(LOG_TAG, "Waiting for image download in separate thread... ${remoteUri.path}")
            existingLatch.await(15, TimeUnit.SECONDS)
            Log.d(LOG_TAG, "... Available!")
            if (!file.exists()) {
                throw FileNotFoundException(uri.path)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val tmpFile = File.createTempFile("dashtune-albumart", ".png", context.cacheDir)
        val request: Request = Request.Builder()
            .url(remoteUri.toString())
            .build()

        // Note: remoteUri may carry an api_key query parameter; only log the path, never the query.
        Log.d(LOG_TAG, "Downloading ${remoteUri.path} ...")
        try {
            client.newCall(request).execute().use {
                if (it.code == 200) {
                    Log.d(LOG_TAG, "Downloaded ${remoteUri.path}")
                    val source = it.body.source()
                    source.request(Long.MAX_VALUE)

                    val sink = tmpFile.sink().buffer()
                    sink.writeAll(source)
                    sink.flush()
                    sink.close()

                    tmpFile.renameTo(file)
                } else {
                    Log.w(LOG_TAG, "Failed to download ${remoteUri.path}: HTTP ${it.code}")
                    FirebaseUtils.safeSetCustomKey("album_art_path", remoteUri.path ?: "unknown")
                    FirebaseUtils.safeRecordException(Exception("Album art download failed: HTTP ${it.code}"))
                }
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Network error downloading ${remoteUri.path}", e)
        } finally {
            tmpFile.delete()
            synchronized(inProgress) {
                inProgress[remoteUri]?.countDown()
                inProgress.remove(remoteUri)
            }
        }

        if (!file.exists()) {
            throw FileNotFoundException(uri.path)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
