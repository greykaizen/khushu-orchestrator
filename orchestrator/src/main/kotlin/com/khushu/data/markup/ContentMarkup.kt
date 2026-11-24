package com.khushu.data.markup

/**
 * Typed inline-span parser for content markup.
 *
 * Verified vocabulary (full-corpus scan, docs/sunnah-plan.md §1.2):
 *   <br> · <b> · <i> · <sup> · <qref>…</qref> (Quran refs) · <ref>…</ref> (hadith refs)
 *   · <hon kind="ra|saw|swt|awj|rh|as|jj|other">…</hon> (honorifics)
 *
 * Unknown tags pass through as LITERAL TEXT — never dropped, never rendered
 * as markup. This is the systematic fix for the Osprey rendering failure.
 */
sealed interface ContentSpan {
    data class Text(val text: String) : ContentSpan
    data object LineBreak : ContentSpan
    data class Bold(val children: List<ContentSpan>) : ContentSpan
    data class Italic(val children: List<ContentSpan>) : ContentSpan
    data class Superscript(val children: List<ContentSpan>) : ContentSpan
    /** Quran reference: raw inner content, e.g. "2:255" — host resolves to ayah links. */
    data class QuranRef(val raw: String, val id: String? = null) : ContentSpan
    /**
     * Hadith/cross reference: raw inner content plus the target hadith id from
     * the `id` attribute (e.g. `<ref id="bukhari_urn_101830">Hadith 183</ref>`).
     * The id is the corpus primary key, so the host can resolve it directly.
     * Null when the markup carried no id — never invent a link.
     */
    data class HadithRef(val raw: String, val id: String? = null) : ContentSpan
    /**
     * A honorific that already exists in the canonical text. The renderer
     * decides presentation (Arabic-only or Arabic + superscript gloss); the
     * parser never adds or removes an honorific.
     */
    data class Honorific(val kind: HonorificKind, val text: String) : ContentSpan
}

enum class HonorificKind {
    RA, SAW, SWT, AWJ, RH, AS, JJ, OTHER;

    companion object {
        fun from(raw: String?): HonorificKind = when (raw?.trim()?.lowercase()) {
            "ra" -> RA
            "saw" -> SAW
            "swt" -> SWT
            "awj" -> AWJ
            "rh" -> RH
            "as" -> AS
            "jj" -> JJ
            else -> OTHER
        }
    }
}

object ContentMarkup {

    // Compiled ONCE. The old parser built 2–3 Regex per block; over the 301 MB
    // hadith index build that is hundreds of thousands of compilations.
    private val TAG_RE = Regex("<(/?)(br|b|i|sup|qref|ref|hon)[^>]*/?>", RegexOption.IGNORE_CASE)
    private val REF_ID_RE = Regex("""\bid\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val HON_KIND_RE = Regex("""\bkind\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val ENTITY_RE = Regex("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);")

    /**
     * Decode the entity subset the canonicalizer emits (`&amp;`, numeric
     * entities such as `&#42;`). Applied to text RUNS after tag parsing, so a
     * decoded `&lt;b&gt;` stays literal text and is never re-interpreted as a tag.
     */
    fun decodeEntities(text: String): String = ENTITY_RE.replace(text) { m ->
        val token = m.groupValues[1]
        when {
            token.startsWith("#x", ignoreCase = true) ->
                token.drop(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp)) } ?: m.value
            token.startsWith("#") ->
                token.drop(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp)) } ?: m.value
            else -> when (token.lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> "\u00a0"
                else -> m.value
            }
        }
    }
    private val QREF_CLOSE_RE = Regex("</qref\\s*>", RegexOption.IGNORE_CASE)
    private val REF_CLOSE_RE = Regex("</ref\\s*>", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ContentSpan> {
        val root = mutableListOf<ContentSpan>()
        val stack = ArrayDeque<MutableList<ContentSpan>>()
        stack.addLast(root)

        var cursor = 0

        while (cursor < text.length) {
            val m = TAG_RE.find(text, cursor)
            if (m == null) {
                val tail = text.substring(cursor)
                if (tail.isNotEmpty()) stack.last().add(ContentSpan.Text(decodeEntities(tail)))
                break
            }
            val before = text.substring(cursor, m.range.first)
            if (before.isNotEmpty()) stack.last().add(ContentSpan.Text(decodeEntities(before)))

            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()

            when {
                // Typed honorific: capture inner text up to its close tag.
                !closing && name == "hon" -> {
                    val openEnd = m.range.last + 1
                    val closeRe = Regex("</hon\\s*>", RegexOption.IGNORE_CASE)
                    val close = closeRe.find(text, openEnd)
                    if (close == null) {
                        stack.last().add(ContentSpan.Text(m.value))
                        cursor = openEnd
                    } else {
                        val inner = text.substring(openEnd, close.range.first)
                        val kindRaw = HON_KIND_RE.find(m.value)?.let { a ->
                            a.groupValues[1].ifEmpty { a.groupValues[2] }.ifEmpty { a.groupValues[3] }
                        }
                        stack.last().add(ContentSpan.Honorific(HonorificKind.from(kindRaw), decodeEntities(inner)))
                        cursor = close.range.last + 1
                    }
                    continue
                }
                // Self-contained custom refs: capture inner content up to close tag.
                !closing && (name == "qref" || name == "ref") -> {
                    val openEnd = m.range.last + 1
                    val closeRe = Regex("</$name\\s*>", RegexOption.IGNORE_CASE)
                    val close = closeRe.find(text, openEnd)
                    if (close == null) {
                        stack.last().add(ContentSpan.Text(m.value))
                        cursor = openEnd
                    } else {
                        val inner = text.substring(openEnd, close.range.first)
                        val id = REF_ID_RE.find(m.value)?.let { a ->
                            a.groupValues[1].ifEmpty { a.groupValues[2] }.ifEmpty { a.groupValues[3] }
                        }?.takeIf { it.isNotEmpty() }
                        stack.last().add(
                            if (name == "qref") ContentSpan.QuranRef(decodeEntities(inner), id)
                            else ContentSpan.HadithRef(decodeEntities(inner), id),
                        )
                        cursor = close.range.last + 1
                    }
                    continue
                }
                !closing && name == "br" -> stack.last().add(ContentSpan.LineBreak)
                closing && (name == "b" || name == "i" || name == "sup") -> {
                    if (stack.size > 1) {
                        val children = stack.removeLast()
                        val span = when (name) {
                            "b" -> ContentSpan.Bold(children.toList())
                            "i" -> ContentSpan.Italic(children.toList())
                            else -> ContentSpan.Superscript(children.toList())
                        }
                        stack.last().add(span)
                    } else {
                        stack.last().add(ContentSpan.Text(m.value))
                    }
                }
                closing -> stack.last().add(ContentSpan.Text(m.value)) // stray close: literal
                else -> { // opening b/i/sup: recurse until its close via nested stack frame
                    stack.addLast(mutableListOf())
                }
            }
            cursor = m.range.last + 1

            // Opening b/i/sup pushed a frame; on matching close we pop above.
        }
        // Malformed input safety: unbalanced open frames merge back into root.
        while (stack.size > 1) {
            val children = stack.removeLast()
            stack.last().addAll(children)
        }
        return flatten(root)
    }

    private fun flatten(spans: List<ContentSpan>): List<ContentSpan> =
        spans.flatMap { s ->
            when (s) {
                is ContentSpan.Bold -> listOf(ContentSpan.Bold(flatten(s.children)))
                is ContentSpan.Italic -> listOf(ContentSpan.Italic(flatten(s.children)))
                is ContentSpan.Superscript -> listOf(ContentSpan.Superscript(flatten(s.children)))
                else -> listOf(s)
            }
        }

    /** Plain-text projection (markup stripped) — for search indexing. */
    fun plainText(spans: List<ContentSpan>): String = buildString {
        for (s in spans) when (s) {
            is ContentSpan.Text -> append(s.text)
            is ContentSpan.LineBreak -> append('\n')
            is ContentSpan.Bold -> append(plainText(s.children))
            is ContentSpan.Italic -> append(plainText(s.children))
            is ContentSpan.Superscript -> append(plainText(s.children))
            is ContentSpan.QuranRef -> append(s.raw)
            is ContentSpan.HadithRef -> append(s.raw)
            is ContentSpan.Honorific -> append(s.text)
        }
    }
}
