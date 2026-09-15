package com.khushu.data.pack

import com.khushu.data.store.SqlStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Pack manifest + storage API over a fake in-memory storage (no FS/network). */
class PackApiTest {

    private val manifest: PackManifest = run {
        val packsRoot =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "data/manifest/packs.json").exists() }
                ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "khushu-data-api/data/manifest/packs.json").exists() }
                    ?.let { File(it, "khushu-data-api") }
        val mf = packsRoot?.let { File(it, "data/manifest/packs.json") }
        check(mf != null && mf.isFile) { "packs.json not found (khushu-data-api sibling checkout required)" }
        PackManifest.parse(mf.readText())
    }

    /** Fake host storage: tracks installed ids + reports install result. */
    private class FakeStorage(private var installed: MutableSet<String> = mutableSetOf()) : PackStorage {
        val deleted = mutableListOf<String>()
        var failIds: Set<String> = emptySet()
        override fun isInstalled(packId: String) = packId in installed
        override fun installedBytes(packId: String) = if (packId in installed) 1024L else 0L
        override fun installedIds() = installed.toSet()
        override suspend fun install(info: PackInfo, url: String, onProgress: (Long) -> Unit): PackInstallResult {
            if (info.id in failIds) return PackInstallResult.Failed("boom")
            require(url.contains("packs-") && url.contains(info.file)) { "bad url: $url" }
            installed += info.id; onProgress(info.bytes)
            return PackInstallResult.Installed(info.bytes)
        }
        override fun delete(packId: String): Boolean { deleted += packId; return installed.remove(packId) }
    }

    @Test fun manifestParsesRealPacksJson() {
        assertEquals("v2026.09", manifest.version)
        assertEquals(104, manifest.all().size)
        assertNotNull(manifest.byId("content"))
        assertEquals(4, manifest.byTier("prebundle").size)
        assertTrue(manifest.byTier("prebundle").any { it.id == "quran-core" })
        // family grouping: 51 translation packs
        assertEquals(51, manifest.byFamily("translation").size)
    }

    @Test fun urlBuildsPerFamilyRelease() {
        val info = manifest.byId("translation-en_saheeh-international")!!
        val url = manifest.url(info)
        assertTrue(url.startsWith("https://github.com/"))
        assertTrue(url.contains("packs-translation-"), url)
        assertTrue(url.endsWith(info.file), url)
    }

    @Test fun installDeleteAndSummary() = runTest {
        val storage = FakeStorage()
        val api = PackApi(manifest, storage)
        // install one family
        val res = api.install("content")
        assertTrue(res is PackInstallResult.Installed)
        assertTrue(api.isInstalled("content"))
        assertTrue(api.install("content") is PackInstallResult.AlreadyPresent) // idempotent
        val s = api.summary()
        assertEquals(setOf("content"), s.installed)
        assertTrue(api.delete("content")); assertFalse(api.isInstalled("content"))
        assertEquals(listOf("content"), storage.deleted)
    }

    @Test fun familyInstallReportsEach() = runTest {
        val storage = FakeStorage().apply { failIds = setOf("wbw-fa") }
        val api = PackApi(manifest, storage)
        val results = api.installFamily("wbw")
        assertEquals(14, results.size)
        assertEquals(13, results.count { it.second is PackInstallResult.Installed })
        assertEquals(1, results.count { it.second is PackInstallResult.Failed })
        assertEquals(13, api.installedIds().size)
    }

    @Test fun storeRouterPrefersLocalThenRemoteThenNull() = runTest {
        val storage = FakeStorage(installed = mutableSetOf("quran-core"))
        val api = PackApi(manifest, storage)
        val remote = object : SqlStore {
            override suspend fun query(sql: String, args: List<Any?>): List<com.khushu.data.store.Row> = emptyList()
        }
        val router = StoreRouter(api, remoteFor = { if (it == "wbw-en") remote else null })
        assertTrue(router.isLocal("quran-core"))
        assertNotNull(router.resolve("wbw-en"))
        assertNull(router.resolve("tafsir-ar-tafsir-al-tabari"))
    }
}
