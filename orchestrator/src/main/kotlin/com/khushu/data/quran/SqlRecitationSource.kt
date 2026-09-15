package com.khushu.data.quran

import com.khushu.data.model.ChapterTimings
import com.khushu.data.model.ReciterInfo
import com.khushu.data.model.VerseTiming
import com.khushu.data.model.WordAudioSegment
import com.khushu.data.store.SqlStore
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SQL-backed recitation index + timings over the `khushu-audio` DB / `audio` pack.
 * Reciters carry the EXTERNAL quranicaudio URL template (audio stays off-repo by
 * design); per-surah timings are stored gzipped in `recitation_timings.timing_gz`
 * and decompressed to the same [ChapterTimings] the JSON path produced.
 */
class SqlRecitationSource(private val audio: SqlStore, private val json: Json = Json { ignoreUnknownKeys = true }) {

    suspend fun reciters(): List<ReciterInfo> = audio.query(
        "SELECT id, reciter, style, url_template, timing_version, audio_version, translations_json " +
            "FROM recitation_reciters ORDER BY reciter",
    ).map { r ->
        val translations = runCatching {
            r.stringAt(6)?.let { t -> json.parseToJsonElement(t).jsonObject.mapValues { it.value.jsonPrimitive.content } }
        }.getOrNull().orEmpty()
        ReciterInfo(
            id = r.stringAt(0) ?: "", name = r.stringAt(1) ?: "", style = r.stringAt(2)?.ifBlank { null },
            urlTemplate = r.stringAt(3), timingUrl = null, timingVersion = r.intAt(4) ?: 1,
            audioVersion = r.intAt(5) ?: 1, translations = translations,
        )
    }

    suspend fun reciter(id: String): ReciterInfo? = reciters().firstOrNull { it.id == id }

    suspend fun timings(reciterId: String): List<ChapterTimings> {
        val blob = audio.query("SELECT timing_gz FROM recitation_timings WHERE reciter=? ORDER BY version DESC LIMIT 1", listOf(reciterId))
            .firstOrNull()?.bytesAt(0) ?: return emptyList()
        val text = GZIPInputStream(ByteArrayInputStream(blob)).reader(Charsets.UTF_8).readText()
        val chapters = json.parseToJsonElement(text).jsonObject["chapters"]?.jsonArray ?: return emptyList()
        return chapters.map { el ->
            val o = el.jsonObject
            val verses = o["verses"]?.jsonArray?.map { v ->
                val vo = v.jsonObject
                VerseTiming(
                    verse = vo["verse"]!!.jsonPrimitive.int,
                    startMs = vo["start_ms"]!!.jsonPrimitive.int,
                    endMs = vo["end_ms"]!!.jsonPrimitive.int,
                    segments = vo["segments"]?.jsonArray?.map { seg ->
                        val s = seg.jsonArray
                        WordAudioSegment(s[0].jsonPrimitive.int, s[1].jsonPrimitive.int, s[2].jsonPrimitive.int)
                    }.orEmpty(),
                )
            }.orEmpty()
            ChapterTimings(reciterId, o["chapter"]!!.jsonPrimitive.int, o["duration_ms"]?.jsonPrimitive?.int ?: 0, verses)
        }
    }

    suspend fun chapterTimings(reciterId: String, surahNo: Int): ChapterTimings? =
        timings(reciterId).firstOrNull { it.chapter == surahNo }
}
