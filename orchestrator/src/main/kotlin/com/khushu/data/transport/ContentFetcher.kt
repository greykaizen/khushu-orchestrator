package com.khushu.data.transport

/**
 * How bytes are obtained. Implementations live OUTSIDE this module's domain —
 * hosts inject HTTP for online mode or file reads for offline mode.
 * Tests use LocalFetcher against a checked-out repo.
 */
fun interface ContentFetcher {
    suspend fun fetch(path: String): ByteArray

    /**
     * Existence probe. Honest by contract:
     * - `true`  — the path definitively exists (body may still be fetched by
     *   the default implementation and discarded — override to make it cheap).
     * - `false` — the path definitively does NOT exist ([ContentMissingException]).
     * - anything else — rethrown. A transport failure is NOT "missing": callers
     *   must never read an offline network as "no such content" (v1.6.0: the
     *   old default swallowed every exception into `false`).
     */
    suspend fun exists(path: String): Boolean = try {
        fetch(path); true
    } catch (e: ContentMissingException) {
        false
    }
}

/** The path is definitively not served by this transport (HTTP 404/410, missing file). */
class ContentMissingException(path: String, cause: Throwable? = null) :
    java.io.IOException("content missing: $path", cause)

/** Reads directly from a local checkout of khushu-data-api. */
class LocalFetcher(private val repoRoot: okio.Path) : ContentFetcher {
    override suspend fun fetch(path: String): ByteArray {
        val file = repoRoot.resolve(path).toFile()
        if (!file.isFile) throw ContentMissingException(path)
        return file.readBytes()
    }

    override suspend fun exists(path: String): Boolean = repoRoot.resolve(path).toFile().isFile
}

/**
 * Fetches from GitHub raw (or any base URL). Requires host-provided HTTP client.
 *
 * [existsProbe] (optional) is the cheap existence check for the remote —
 * e.g. a HEAD request answering 404/410 → false, 2xx → true. Without it the
 * interface default applies: full fetch, and only [ContentMissingException]
 * (which the host's client should throw for 404/410) counts as missing.
 */
class RemoteFetcher(
    private val baseUrl: String,
    private val httpClient: suspend (String) -> ByteArray,
    private val existsProbe: (suspend (String) -> Boolean)? = null,
) : ContentFetcher {
    override suspend fun fetch(path: String): ByteArray = httpClient(url(path))

    override suspend fun exists(path: String): Boolean =
        existsProbe?.invoke(url(path)) ?: super.exists(path)

    private fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path
}
