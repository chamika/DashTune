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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlbumArtContentProvider : ContentProvider() {

    private val client = OkHttpClient()

    companion object {
        private val uriMap = mutableMapOf<Uri, Uri>()
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
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = this.context ?: return null
        val remoteUri = uriMap[uri] ?: throw FileNotFoundException(uri.path)
        val file = File(context.cacheDir, uri.path ?: throw FileNotFoundException("null path"))

        if (file.exists()) {
            Log.d(LOG_TAG, "Returning existing file for $remoteUri: $file")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        synchronized(inProgress) {
            if (inProgress.contains(remoteUri)) {
                Log.d(LOG_TAG, "Waiting for image download in separate thread... $remoteUri")
                inProgress.get(remoteUri)?.await(15, TimeUnit.SECONDS)
                Log.d(LOG_TAG, "... Available!")
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            inProgress.put(remoteUri, CountDownLatch(1))
        }

        val tmpFile = File.createTempFile("dashtune-albumart", ".png", context.cacheDir)
        val request: Request = Request.Builder()
            .url(remoteUri.toString())
            .build()

        Log.d(LOG_TAG, "Downloading $remoteUri ...")
        client.newCall(request).execute().use {
            if (it.body != null && it.code == 200) {
                Log.d(LOG_TAG, "Downloaded $remoteUri")
                val source = it.body!!.source()
                source.request(Long.MAX_VALUE)

                val sink = tmpFile.sink().buffer()
                sink.writeAll(source)
                sink.flush()
                sink.close()

                tmpFile.renameTo(file)
            } else {
                Log.w(LOG_TAG, "Failed to download $remoteUri: \n ${it.code} - ${it.body}")
            }

            inProgress.get(remoteUri)?.countDown()
            inProgress.remove(remoteUri)
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
