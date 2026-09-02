package com.khushu.data.markup

/**
 * Typed inline-span parser for content markup.
 *
 * Verified vocabulary (full-corpus scan, docs/sunnah-plan.md §1.2):
 *   <br> · <b> · <i> · <sup> · <qref>…</qref> (Quran refs) · <ref>…</ref> (hadith refs)
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
    data class QuranRef(val raw: String) : ContentSpan
    /** Hadith/cross reference: raw inner content — host resolves to hadith links. */
    data class HadithRef(val raw: String) : ContentSpan
}

object ContentMarkup {

    fun parse(text: String): List<ContentSpan> {
        val root = mutableListOf<ContentSpan>()
        val stack = ArrayDeque<MutableList<ContentSpan>>()
        stack.addLast(root)

        val tagRe = Regex("<(/?)(br|b|i|sup|qref|ref)[^>]*/?>", RegexOption.IGNORE_CASE)
        var cursor = 0

        while (cursor < text.length) {
            val m = tagRe.find(text, cursor)
            if (m == null) {
                val tail = text.substring(cursor)
                if (tail.isNotEmpty()) stack.last().add(ContentSpan.Text(tail))
                break
            }
            val before = text.substring(cursor, m.range.first)
            if (before.isNotEmpty()) stack.last().add(ContentSpan.Text(before))

            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()

            when {
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
                        stack.last().add(
                            if (name == "qref") ContentSpan.QuranRef(inner)
                            else ContentSpan.HadithRef(inner),
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
        }
    }
}
