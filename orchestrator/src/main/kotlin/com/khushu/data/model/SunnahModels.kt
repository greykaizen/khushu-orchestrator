package com.khushu.data.model

import com.khushu.data.markup.ContentSpan

/** Verified block vocabulary (full-corpus scan — docs/sunnah-plan.md §1.1). */
enum class BlockType { MATN, SANAD, NARRATOR, NOTE, UNKNOWN }

/**
 * One content block of a hadith: typed classification plus parsed inline
 * spans (markup → ContentSpan) and the untouched raw text.
 */
data class ContentBlock(
    val type: BlockType,
    val rawType: String,
    val spans: List<ContentSpan>,
    val rawText: String,
)

data class Reference(val type: String, val value: String)

data class Grade(val gradeId: String, val label: String, val lang: String)

data class NarratorRef(val source: String, val narratorId: Int, val position: Int)

data class Hadith(
    val id: String,
    val urn: Long?,
    val collectionId: String,
    val bookId: String,
    val chapterId: String?,
    val number: String?,
    val blocks: List<ContentBlock>,
    val references: List<Reference>,
    val relatedIds: List<String>,
    val grades: List<Grade>,
    val narratorRefs: List<NarratorRef>,
    /** Languages with available content for this hadith. */
    val contentLangs: List<String> = emptyList(),
)

data class HadithCollection(
    val id: String,
    val type: String,
    val sortOrder: Int,
    val hasVolumes: Boolean,
    val hasBooks: Boolean,
    val hasChapters: Boolean,
    val numberingSource: String?,
    val title: String?,
    val intro: String?,
    val description: String?,
    /** CorpusBundle schema stamped into the .db (`bundle_meta` table). */
    val schemaVersion: Int? = null,
    /** Content identity (sha256 prefix at ingestion) stamped into the .db. */
    val contentVersion: String? = null,
)

data class Book(
    val id: String,
    val collectionId: String,
    val number: String,
    val title: String?,
    val intro: String?,
    val preamble: String?,
    val notes: String?,
)

data class Chapter(
    val id: String,
    val collectionId: String,
    val bookId: String,
    val number: String,
    val title: String?,
)

/** Narrator biographical record (scholars_info.db). */
data class Scholar(
    val id: Long,
    val shortName: String?,
    val fullName: String?,
    val arabicName: String?,
    val rank: Int?,
    val birthDate: String?,
    val birthPlace: String?,
    val deathDate: String?,
    val deathPlace: String?,
    val bio: String?,
    val teachers: String?,
    val students: String?,
    val kunya: String?,
)

data class SearchResultRow(
    val hadithId: String,
    val snippet: String,
)
