package com.lelloman.simpleaiassistant.diagnostics

import kotlinx.serialization.json.*

/** Best-effort secret removal, not a promise that free text is anonymous. */
object DiagnosticRedactor {
    private const val REDACTED = "[REDACTED]"
    const val TRUNCATED = "[truncated]"
    private val credentials = Regex("(?i)\\b(?:bearer|basic)\\s+[a-z0-9._~+/=-]+")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val apiKey = Regex("\\b(?:sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{20,})")
    private val assignment = Regex("(?i)(password|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|cookie)\\s*[=:]\\s*[^\\s,;]+")
    fun text(value: String, limit: Int = 64 * 1024): String {
        // Redact before truncation so a shortened credential cannot escape recognition.
        val safe = assignment.replace(apiKey.replace(jwt.replace(credentials.replace(value, REDACTED), REDACTED), REDACTED)) { "${it.groupValues[1]}=$REDACTED" }
        return utf8Prefix(safe, limit)
    }
    fun utf8Prefix(value: String, limit: Int): String {
        val bytes=value.toByteArray(Charsets.UTF_8)
        if(bytes.size<=limit) return value
        var end=maxOf(0,limit-TRUNCATED.length)
        while(end>0 && (bytes[end].toInt() and 0xc0)==0x80) end--
        return String(bytes,0,end,Charsets.UTF_8)+TRUNCATED
    }
    private fun sensitive(key: String): Boolean {
        val normalized=key.filter { it.isLetterOrDigit() }.lowercase()
        return normalized in setOf("authorization","cookie","setcookie","credential","credentials","reasoning","reasoningcontent","thinking","systemprompt","headers") ||
            normalized.contains("password") || normalized.contains("secret") || normalized.endsWith("token") || normalized.endsWith("apikey")
    }
    fun value(value: Any?, depth: Int = 0): JsonElement {
        if (depth > 12) return JsonPrimitive(TRUNCATED)
        return when (value) {
            null -> JsonNull
            is JsonObject -> JsonObject(value.entries.take(128).associate { (key,v) -> text(key,256) to if(sensitive(key)) JsonPrimitive(REDACTED) else value(v,depth+1) })
            is Map<*,*> -> JsonObject(value.entries.take(128).associate { (key,v) ->
                val name=key as? String ?: "unsupported_key"
                text(name,256) to if(sensitive(name)) JsonPrimitive(REDACTED) else value(v,depth+1)
            })
            is JsonArray -> JsonArray(value.take(128).map { value(it,depth+1) })
            is Iterable<*> -> JsonArray(value.take(128).map { value(it,depth+1) })
            is JsonPrimitive -> if(value.isString) JsonPrimitive(text(value.content,8192)) else value
            is String -> {
                val parsed = if(value.length<=128*1024 && (value.trimStart().startsWith("{") || value.trimStart().startsWith("["))) runCatching { Json.parseToJsonElement(value) }.getOrNull() else null
                if(parsed!=null) value(parsed,depth+1) else JsonPrimitive(text(value,8192))
            }
            is Number -> JsonPrimitive(value.toString().take(64))
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive("[unsupported value]")
        }
    }
}
