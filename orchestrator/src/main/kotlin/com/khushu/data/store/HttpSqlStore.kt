package com.khushu.data.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Remote [SqlStore] executing READ-ONLY SQL against a Turso database via the
 * HTTP `/v2/pipeline` batch API — no libSQL driver, so it depends on no native
 * and no Android-only SDK. The host injects [post] (it attaches the per-DB
 * read-only bearer token + does the network); this class owns only the request
 * shape and response mapping.
 *
 * Write attempts are rejected server-side by the read-only token; the pipeline
 * error is surfaced as [TursoQueryException] (never swallowed into "no rows").
 *
 * @param endpointUrl base URL, e.g. `https://khushu-quran-syedali.aws-us-east-1.turso.io`
 *        (the `libsql://` form's https equivalent). `/v2/pipeline` is appended.
 * @param post transport: sends [body] JSON to [url], returns the response text.
 */
class HttpSqlStore(
    endpointUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val post: suspend (url: String, body: String) -> String,
) : SqlStore {

    private val pipelineUrl = endpointUrl.trimEnd('/') + "/v2/pipeline"

    override suspend fun query(sql: String, args: List<Any?>): List<Row> {
        val body = buildRequest(sql, args)
        val text = post(pipelineUrl, body)
        return parse(text)
    }

    // ── request ──────────────────────────────────────────────────────────────
    internal fun buildRequest(sql: String, args: List<Any?>): String {
        val argsJson = args.joinToString(",", "[", "]") { arg ->
            when (arg) {
                null -> """{"type":"null"}"""
                is Boolean -> """{"type":"integer","value":"${if (arg) 1 else 0}"}"""
                is Int, is Long, is Short, is Byte -> """{"type":"integer","value":"$arg"}"""
                is Float, is Double -> """{"type":"float","value":"$arg"}"""
                is ByteArray -> """{"type":"blob","value":"${java.util.Base64.getEncoder().encodeToString(arg)}"}"""
                else -> """{"type":"text","value":"${arg.toString().jsonEscape()}"}"""
            }
        }
        // batch/execute with field name "sql" (this Turso version).
        return """{"requests":[{"type":"batch","batch":{"steps":[{"type":"execute",""" +
            """"stmt":{"sql":"${sql.jsonEscape()}","args":$argsJson}}]}}]}"""
    }

    // ── response ──────────────────────────────────────────────────────────────
    internal fun parse(text: String): List<Row> {
        val root = json.parseToJsonElement(text).jsonObject
        root["error"]?.let { throw TursoQueryException(it.jsonPrimitive.content) }
        val first = (root["results"]?.jsonArray?.firstOrNull() as? JsonObject)
            ?: throw TursoQueryException("empty pipeline results")
        if ((first["type"] as? JsonPrimitive)?.content != "ok") {
            throw TursoQueryException((first["error"] as? JsonObject)
                ?.let { "${it["code"]?.jsonPrimitive?.content}: ${it["message"]?.jsonPrimitive?.content}" }
                ?: "pipeline error")
        }
        val batch = first["response"]?.jsonObject?.get("result")?.jsonObject
            ?: throw TursoQueryException("no batch result")
        // A step error (e.g. the read-only token rejecting a write) surfaces here,
        // even though the outer envelope is still type=ok.
        (batch["step_errors"]?.jsonArray?.firstOrNull())?.let { e ->
            if (e !is JsonNull) throw TursoQueryException(
                "${e.jsonObject["code"]?.jsonPrimitive?.content}: ${e.jsonObject["message"]?.jsonPrimitive?.content}")
        }
        val step = batch["step_results"]?.jsonArray?.firstOrNull() as? JsonObject
            ?: throw TursoQueryException("no step_results")
        val cols = step["cols"]?.jsonArray?.map { (it as JsonObject)["name"]!!.jsonPrimitive.content }
            ?: emptyList()
        return step["rows"]?.jsonArray?.map { r ->
            val values = HashMap<String, Any?>(cols.size)
            (r as JsonArray).forEachIndexed { i, el -> if (i < cols.size) values[cols[i]] = el.toValue() }
            HttpRow(cols, values)
        } ?: emptyList()
    }

    private fun JsonElement.toValue(): Any? {
        val o = this as? JsonObject ?: return (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }
        return when (o["type"]?.jsonPrimitive?.content) {
            "integer" -> o["value"]?.jsonPrimitive?.content?.toLongOrNull()
            "float" -> o["value"]?.jsonPrimitive?.content?.toDoubleOrNull()
            "blob" -> o["value"]?.jsonPrimitive?.content
            "null" -> null
            else -> o["value"]?.jsonPrimitive?.content
        }
    }

    private fun String.jsonEscape(): String = buildString {
        for (c in this@jsonEscape) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c)
        }
    }
}

private class HttpRow(override val columns: List<String>, private val v: Map<String, Any?>) : Row {
    private fun num(c: String): Number? = v[c] as? Number
    override fun isNull(column: String) = v[column] == null
    override fun string(column: String): String? = v[column]?.toString()
    override fun int(column: String): Int? = num(column)?.toInt() ?: v[column]?.toString()?.toIntOrNull()
    override fun long(column: String): Long? = num(column)?.toLong() ?: v[column]?.toString()?.toLongOrNull()
    override fun double(column: String): Double? = num(column)?.toDouble() ?: v[column]?.toString()?.toDoubleOrNull()
    override fun any(column: String): Any? = v[column]
}

/** A Turso pipeline error (including a read-only token rejecting a write). */
class TursoQueryException(message: String?) : RuntimeException("turso query failed: $message")
