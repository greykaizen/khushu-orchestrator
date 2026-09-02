package com.khushu.data.model

/**
 * One dua/dhikr entry from `assets/dua_dhikr/` (lifewithallah corpus,
 * 491 entries). Arabic + translation + transliteration are plain text;
 * `repetition` is the donor's display string (e.g. "(1x)", "(3x)").
 */
data class Dua(
    /** Stable entry id (1..491 in the canonical corpus). */
    val id: Int,
    /** Donor post id (lifewithallah.com scrape provenance). */
    val postId: Int,
    /** Category display title (e.g. "Morning & Evening"). */
    val postTitle: String,
    /** Top-level category key: `main-adhkar` or `other-adhkar`. */
    val category: String,
    /** Subcategory slug (e.g. `morning-evening`, `marriage-and-children`). */
    val subcategory: String,
    val title: String,
    val arabic: String,
    val repetition: String,
    val translation: String,
    val transliteration: String,
    val virtue: String,
    val explanation: String,
    /** Remote recitation URL (donor CDN); null for 2 entries. Local `.opus` mirrors exist as `dua_{id}.opus`. */
    val audioUrl: String?,
    /** Donor citation (e.g. "Abū Dāwūd 2130"). */
    val reference: String?,
)

/** A dua subcategory group: slug + localized display info. */
data class DuaCategory(
    val category: String,
    val subcategory: String,
    val postTitle: String,
    val count: Int,
)

/**
 * One of the 99 Names of Allah (`assets/asma_ul_husna/asma_data_{lang}.json`).
 */
data class AsmaName(
    val number: Int,
    val name: String,
    val transliteration: String,
    val translation: String,
    val meaning: String,
    /** Remote recitation URL for this name. */
    val audio: String?,
)

/** Language pack of the 99 Names. */
data class AsmaPack(
    val langCode: String,
    val title: String,
    val description: String?,
    val hadith: String?,
    val recitationBenefits: String?,
    val total: Int,
    val names: List<AsmaName>,
)

/** Index entry for a dua/dhikr reading article (HTML content). */
data class DuaArticleInfo(
    val id: Int,
    val title: String,
    val slug: String,
    /** Donor source link (lifewithallah.com). */
    val link: String?,
    /** Repo-relative path of the JSON wrapper (under `assets/dua_dhikr/`). */
    val filePath: String,
)

/** One article category from `articles_index.json`. */
data class DuaArticleCategory(
    val id: Int,
    val name: String,
    val slug: String,
    val count: Int,
    val articles: List<DuaArticleInfo>,
)

/** A reading article: metadata + RAW HTML content (host renders natively). */
data class DuaArticle(
    val id: Int,
    val title: String,
    val slug: String,
    val link: String?,
    /** Raw HTML body — the host's markup stack renders it (passthrough contract). */
    val contentHtml: String,
)
