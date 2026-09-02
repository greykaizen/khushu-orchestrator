package com.khushu.data.transport

/**
 * How bytes are obtained. Implementations live OUTSIDE this module's domain —
 * hosts inject HTTP for online mode or file reads for offline mode.
 * Tests use LocalFetcher against a checked-out repo.
 */
fun interface ContentFetcher {
    suspend fun fetch(path: String): ByteArray
}

/** Reads directly from a local checkout of khushu-data-api. */
class LocalFetcher(private val repoRoot: okio.Path) : ContentFetcher {
    override suspend fun fetch(path: String): ByteArray {
        val file = repoRoot.resolve(path)
        return java.io.FileInputStream(file.toFile()).readBytes()
    }
}

/** Fetches from GitHub raw (or any base URL). Requires host-provided HTTP client. */
class RemoteFetcher(
    private val baseUrl: String,
    private val httpClient: suspend (String) -> ByteArray,
) : ContentFetcher {
    override suspend fun fetch(path: String): ByteArray = httpClient(baseUrl.trimEnd('/') + "/" + path)
}
