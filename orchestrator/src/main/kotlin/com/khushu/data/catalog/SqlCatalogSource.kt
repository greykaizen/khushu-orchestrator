package com.khushu.data.catalog

import com.khushu.data.model.CatalogEntry
import com.khushu.data.model.FontFileEntry
import com.khushu.data.model.FontPackEntry
import com.khushu.data.store.SqlStore

/**
 * SQL-backed catalog listings over the `content` pack (+ optionally `quran-core` for the
 * translation list). Replaces the JSON `CatalogRepository` fetches with DB reads:
 *   fonts    -> font_packs + font_files (display name/usage/weight/provenance)
 *   webLinks -> links
 *   translations -> translation_packs (when a quran store is supplied)
 * tafsirs/wbw listings come from their own stores (SqlTafsirSource.books, wbw_packs).
 */
class SqlCatalogSource(private val content: SqlStore, private val quran: SqlStore? = null) {

    suspend fun fonts(): List<FontPackEntry> {
        val files = content.query(
            "SELECT pack, id, display_name, asset_path, format, weight, provenance FROM font_files ORDER BY pack, id",
        ).groupBy({ it.stringAt(0) ?: "" }, {
            FontFileEntry(id = it.stringAt(1) ?: "", displayName = it.stringAt(2) ?: "", path = it.stringAt(3) ?: "",
                format = it.stringAt(4) ?: "ttf", weight = it.intAt(5) ?: 400, provenance = it.stringAt(6))
        })
        return content.query("SELECT id, display_name, usage FROM font_packs ORDER BY id").map { r ->
            val id = r.stringAt(0) ?: return@map null
            FontPackEntry(id = id, displayName = r.stringAt(1) ?: "", usage = r.stringAt(2), files = files[id].orEmpty())
        }.filterNotNull()
    }

    suspend fun webLinks(): Map<String, String> =
        content.query("SELECT key, url FROM links").associate { it.stringAt(0)!! to (it.stringAt(1) ?: "") }

    /** Translation packs for a language (uses the `quran` store when provided). */
    suspend fun translations(lang: String? = null): List<CatalogEntry> {
        val store = quran ?: return emptyList()
        val rows = if (lang == null)
            store.query("SELECT pack_id, lang_code, display_name, version, path FROM translation_packs ORDER BY lang_code, pack_id")
        else
            store.query("SELECT pack_id, lang_code, display_name, version, path FROM translation_packs WHERE lang_code=? ORDER BY pack_id", listOf(lang))
        return rows.map { r ->
            CatalogEntry(id = r.stringAt(0) ?: "", langCode = r.stringAt(1), displayName = r.stringAt(2) ?: "",
                version = (r.intAt(3) ?: 1).toLong(), url = r.stringAt(4))
        }
    }
}
