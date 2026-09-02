package com.khushu.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * License-completeness guard (docs/quran-mod-plan.md §6.5): every top-level
 * `inventory` and `assets` subdirectory must be covered by a row in the
 * LICENSE-CONTENT.md table — packs without a row must never ship.
 */
class LicenseCompletenessTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "LICENSE-CONTENT.md").exists() }
            // absorbed-test resolver: content corpus lives in the sibling checkout
            ?: generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "khushu-quran-data/LICENSE-CONTENT.md").exists() }
                ?.let { File(it, "khushu-quran-data") }
            ?: error("repo root (LICENSE-CONTENT.md) not found from user.dir")

    /** First-cell table paths reduced to directory prefixes (glob segments dropped). */
    private fun rowPrefixes(): List<String> =
        File(repoRoot, "LICENSE-CONTENT.md").readLines()
            .filter { it.startsWith("|") && it.contains('`') }
            .mapNotNull { line ->
                val first = line.substringAfter('|').substringBefore('|').trim()
                if (!first.startsWith('`')) return@mapNotNull null
                val segments = first.trim('`').trimStart('/').split('/')
                val prefix = segments.takeWhile { '*' !in it }.joinToString("/")
                if (prefix.isEmpty()) null else "$prefix/"
            }

    @Test
    fun every_inventory_and_assets_directory_has_a_license_row() {
        val prefixes = rowPrefixes()
        assertTrue(prefixes.isNotEmpty(), "no LICENSE-CONTENT.md table rows parsed")

        listOf("inventory", "assets").forEach { root ->
            File(repoRoot, root).listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { dir ->
                    val rel = "$root/${dir.name}/"
                    val covered = prefixes.any { p -> rel.startsWith(p) || p.startsWith(rel) }
                    assertTrue(covered, "LICENSE-CONTENT.md has no row covering $rel")
                }
        }
    }
}
