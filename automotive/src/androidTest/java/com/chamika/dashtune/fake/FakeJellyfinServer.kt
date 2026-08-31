package com.chamika.dashtune.fake

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.io.Closeable
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** One request the app made, flattened so tests never touch MockWebServer types. */
data class ServerRequest(
    val method: String,
    val path: String,
    val url: HttpUrl,
    val authorization: String?,
    val body: String,
) {
    fun query(name: String): String? = url.queryParameter(name)
    fun queryValues(name: String): List<String?> = url.queryParameterValues(name)
}

/**
 * A simulated Jellyfin server, served in-process on 127.0.0.1.
 *
 * Instrumentation runs inside the app's own process, so the loopback address the test binds
 * is the same one `DashTuneMusicService` reaches — no host networking and no `10.0.2.2`.
 * Pointing the app at it needs only an account row and a `LOGIN_COMMAND`; see
 * [com.chamika.dashtune.support.DashTuneE2eRule].
 *
 * Requests are answered by filtering the flat [FakeLibrary] item list with the same
 * parameters Jellyfin itself accepts, so one pipeline covers every browse call the app makes.
 * **Any route this does not implement returns 501 and is recorded**, so a missing endpoint
 * shows up as a loud failure rather than a silently empty browse list.
 */
class FakeJellyfinServer(
    val fixture: DashTuneFixture = DashTuneFixture(),
) : Closeable {

    private val server = MockWebServer()
    private val recorded = Collections.synchronizedList(mutableListOf<ServerRequest>())

    /** Fixed seed so `sortBy=Random` is stable across calls and pagination stays comparable. */
    private val shuffleSeed = 20_260_831L

    val library: FakeLibrary get() = fixture.library

    @Volatile
    var behavior: ServerBehavior = ServerBehavior.Healthy

    /** Every request the app has made, oldest first. */
    val requests: List<ServerRequest> get() = synchronized(recorded) { recorded.toList() }

    lateinit var baseUrl: String
        private set

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.port}"
    }

    override fun close() {
        server.close()
    }

    fun clearRequests() = synchronized(recorded) { recorded.clear() }

    fun requestsTo(pathPrefix: String): List<ServerRequest> =
        requests.filter { it.path.startsWith(pathPrefix) }

    /** Path segments the app hit, useful when a 501 shows up in a failure message. */
    fun unhandledPaths(): List<String> = requests.map { it.path }.distinct()

    // ---------------------------------------------------------------- dispatch

    private fun handle(request: RecordedRequest): MockResponse {
        val url = request.url
        val body = request.body?.utf8().orEmpty()
        recorded += ServerRequest(
            method = request.method,
            path = url.encodedPath,
            url = url,
            authorization = request.headers["Authorization"],
            body = body,
        )

        val segments = url.pathSegments.filter { it.isNotEmpty() }

        // Album art is fetched by AlbumArtContentProvider on a bare OkHttp client with no
        // Authorization header, and it discards anything that is not exactly 200 — so it is
        // answered before the behaviour switch, which only models API faults.
        if (segments.size >= 4 && segments[0] == "Items" && segments[2] == "Images") {
            return bytes(FakeAudio.png, "image/png")
        }

        // Media is streamed by ExoPlayer, which issues Range requests.
        if (segments.size == 3 && segments[0] == "Audio" && segments[2] == "universal") {
            return audio(request)
        }

        when (val current = behavior) {
            ServerBehavior.Healthy -> Unit
            ServerBehavior.Unauthorized -> return MockResponse.Builder().code(401).build()
            ServerBehavior.ServerError -> return MockResponse.Builder().code(500).build()
            ServerBehavior.MalformedJson ->
                return json("{\"Items\": [ this is not json ")
            is ServerBehavior.Hang -> return MockResponse.Builder()
                .code(200)
                .headersDelay(current.delayMillis, TimeUnit.MILLISECONDS)
                .body("{}")
                .build()
        }

        return when {
            segments == listOf("System", "Ping") -> json("\"Jellyfin Server\"")

            segments == listOf("Items", "Latest") -> json(
                JellyfinJson.encodeArray(filter(url).take(limitOf(url) ?: 120).map { it.toDto() })
            )

            segments == listOf("Items") -> itemsQuery(url)

            segments == listOf("UserViews") ->
                json(JellyfinJson.encode(queryResult(library.views)))

            segments == listOf("Artists", "AlbumArtists") ->
                itemsQuery(url, restrictTo = setOf(BaseItemKind.MUSIC_ARTIST))

            segments == listOf("Genres") ->
                itemsQuery(url, restrictTo = setOf(BaseItemKind.MUSIC_GENRE))

            // GET /Items/{itemId}
            segments.size == 2 && segments[0] == "Items" -> {
                val item = segments[1].asUuid()?.let { library.byId(it) }
                    ?: return MockResponse.Builder().code(404).build()
                json(JellyfinJson.encode(item.toDto()))
            }

            // POST /UserItems/{itemId}/UserData — audiobook position persistence.
            segments.size == 3 && segments[0] == "UserItems" && segments[2] == "UserData" ->
                MockResponse.Builder().code(204).build()

            segments.firstOrNull() == "Sessions" -> MockResponse.Builder().code(204).build()

            segments.firstOrNull() == "UserFavoriteItems" -> MockResponse.Builder().code(204).build()

            else -> MockResponse.Builder().code(501)
                .body("Unhandled route: ${request.method} ${url.encodedPath}")
                .build()
        }
    }

    // ---------------------------------------------------------------- queries

    /**
     * [restrictTo] is set for the dedicated `/Genres` and `/Artists/AlbumArtists` endpoints,
     * which always answer with that one kind. On those routes `includeItemTypes` selects which
     * items are *considered* (the app asks for genres of MusicAlbum) rather than what comes
     * back, so honouring it as a response filter would correctly return nothing.
     */
    private fun itemsQuery(url: HttpUrl, restrictTo: Set<BaseItemKind>? = null): MockResponse {
        var results = filter(url, ignoreItemTypes = restrictTo != null)
        if (restrictTo != null) results = results.filter { it.kind in restrictTo }

        val total = results.size
        val startIndex = url.queryParameter("startIndex")?.toIntOrNull() ?: 0
        val limit = limitOf(url)

        var page = results.drop(startIndex)
        if (limit != null) page = page.take(limit)

        return json(JellyfinJson.encode(queryResult(page, startIndex, total)))
    }

    private fun limitOf(url: HttpUrl): Int? = url.queryParameter("limit")?.toIntOrNull()

    /** The shared filter pipeline: the same parameters a real Jellyfin `/Items` call accepts. */
    private fun filter(url: HttpUrl, ignoreItemTypes: Boolean = false): List<FakeItem> {
        val parentId = url.queryParameter("parentId")?.asUuid()
        val recursive = url.queryParameter("recursive")?.toBoolean() == true

        var results = when {
            parentId != null && recursive -> library.descendantsOf(parentId)
            parentId != null -> library.childrenOf(parentId)
            else -> library.items
        }

        val kinds = if (ignoreItemTypes) emptyList() else url.queryParameterValues("includeItemTypes")
            .mapNotNull { value -> BaseItemKind.entries.firstOrNull { it.serialName == value } }
        if (kinds.isNotEmpty()) results = results.filter { it.kind in kinds }

        val genreIds = url.queryParameterValues("genreIds").mapNotNull { it?.asUuid() }
        if (genreIds.isNotEmpty()) results = results.filter { it.genreIds.any(genreIds::contains) }

        val artistIds = url.queryParameterValues("albumArtistIds").mapNotNull { it?.asUuid() }
        if (artistIds.isNotEmpty()) {
            results = results.filter { it.albumArtistIds.any(artistIds::contains) }
        }

        url.queryParameter("nameStartsWith")?.let { prefix ->
            results = results.filter { it.name.startsWith(prefix, ignoreCase = true) }
        }

        // Jellyfin has no "non-letter" filter; the app asks for nameLessThan=A to collect
        // the "#" bucket, so compare on the first character the same way.
        url.queryParameter("nameLessThan")?.let { bound ->
            results = results.filter {
                it.name.uppercase(Locale.ROOT).take(1) < bound.uppercase(Locale.ROOT).take(1)
            }
        }

        if (url.queryParameterValues("filters").any { it == "IsFavorite" }) {
            results = results.filter { it.isFavorite }
        }

        url.queryParameter("searchTerm")?.let { term ->
            results = results.filter { it.name.contains(term, ignoreCase = true) }
        }

        return sort(results, url)
    }

    private fun sort(items: List<FakeItem>, url: HttpUrl): List<FakeItem> {
        val sortBy = url.queryParameterValues("sortBy").filterNotNull()
        val descending = url.queryParameterValues("sortOrder")
            .any { it == SortOrder.DESCENDING.serialName }

        val sorted = when {
            sortBy.contains(ItemSortBy.RANDOM.serialName) ->
                items.shuffled(Random(shuffleSeed))

            sortBy.contains(ItemSortBy.DATE_CREATED.serialName) ->
                items.sortedBy { it.createdOrder }

            // The app's default for container children: disc, then track, then name.
            sortBy.contains(ItemSortBy.PARENT_INDEX_NUMBER.serialName) ->
                items.sortedWith(
                    compareBy(
                        { it.parentIndexNumber ?: 0 },
                        { it.indexNumber ?: 0 },
                        { it.name },
                    )
                )

            sortBy.contains(ItemSortBy.SORT_NAME.serialName) -> items.sortedBy { it.name }

            // ItemSortBy.DEFAULT preserves insertion order, which is how playlists keep theirs.
            else -> items
        }

        return if (descending) sorted.reversed() else sorted
    }

    // ---------------------------------------------------------------- responses

    private fun json(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun bytes(data: ByteArray, contentType: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", contentType)
        .addHeader("Accept-Ranges", "bytes")
        .body(Buffer().write(data))
        .build()

    /**
     * Serves the tone with `Range` support. CacheDataSource and the prefetch DownloadManager
     * both issue ranged GETs, and a server that ignores them breaks seeking and partial
     * cache reads.
     */
    private fun audio(request: RecordedRequest): MockResponse {
        val data = FakeAudio.wav
        val range = request.headers["Range"]
            ?: return bytes(data, "audio/wav")

        val match = Regex("bytes=(\\d*)-(\\d*)").find(range)
            ?: return bytes(data, "audio/wav")

        val start = match.groupValues[1].toIntOrNull() ?: 0
        val end = match.groupValues[2].toIntOrNull() ?: (data.size - 1)
        if (start >= data.size) {
            return MockResponse.Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */${data.size}")
                .build()
        }

        val clampedEnd = end.coerceAtMost(data.size - 1)
        val slice = data.copyOfRange(start, clampedEnd + 1)
        return MockResponse.Builder()
            .code(206)
            .addHeader("Content-Type", "audio/wav")
            .addHeader("Accept-Ranges", "bytes")
            .addHeader("Content-Range", "bytes $start-$clampedEnd/${data.size}")
            .body(Buffer().write(slice))
            .build()
    }
}

/** Jellyfin accepts UUIDs with or without dashes, so normalise before parsing. */
internal fun String.asUuid(): UUID? {
    val bare = replace("-", "")
    if (bare.length != 32) return null
    return runCatching {
        UUID.fromString(
            "${bare.substring(0, 8)}-${bare.substring(8, 12)}-${bare.substring(12, 16)}-" +
                "${bare.substring(16, 20)}-${bare.substring(20)}"
        )
    }.getOrNull()
}
