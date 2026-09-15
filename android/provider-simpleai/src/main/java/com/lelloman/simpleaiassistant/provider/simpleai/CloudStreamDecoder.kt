package com.lelloman.simpleaiassistant.provider.simpleai

import com.lelloman.simpleaiassistant.model.StreamEvent
import kotlinx.serialization.json.*

/** Tool deltas are never executable until a complete, successful stream terminates. */
internal class CloudStreamDecoder {
    private data class Pending(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )
    private val tools = sortedMapOf<Int, Pending>()
    private var finishReason: String? = null
    private var size = 0
    var finished = false
        private set

    fun accept(data: String): List<StreamEvent> {
        check(!finished) { "Stream already finished" }
        size += data.length
        require(size <= 4 * 1024 * 1024) { "Cloud response too large" }
        if (data == "[DONE]") {
            require(finishReason == "stop" || finishReason == "tool_calls") {
                "Cloud stream did not finish successfully: $finishReason"
            }
            // Validate ALL calls before returning any of them.
            val calls = tools.values.map { call ->
                require(call.id.isNotBlank() && call.name.isNotBlank()) { "Incomplete tool call" }
                StreamEvent.ToolUse(
                    call.id, call.name,
                    Json.parseToJsonElement(call.arguments.toString()).jsonObject
                        .mapValues { nativeValue(it.value) }
                )
            }
            require(calls.map { it.id }.distinct().size == calls.size) { "Duplicate tool call IDs" }
            finished = true
            return calls + StreamEvent.Done
        }
        val chunk = Json.parseToJsonElement(data).jsonObject
        chunk["error"]?.takeUnless { it is JsonNull }?.let {
            finished = true
            return listOf(StreamEvent.Error(
                (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "Cloud stream failed"
            ))
        }
        val events = mutableListOf<StreamEvent>()
        chunk["choices"]?.jsonArray?.forEach { value ->
            val choice = value.jsonObject
            if ((choice["index"]?.jsonPrimitive?.intOrNull ?: 0) != 0) return@forEach
            val delta = choice["delta"]?.takeUnless { it is JsonNull }?.jsonObject
            delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                events.add(StreamEvent.Text(it))
            }
            delta?.get("tool_calls")?.takeUnless { it is JsonNull }?.jsonArray?.forEach { item ->
                val call = item.jsonObject
                // SimpleAI's Ollama adapter emits complete, unindexed calls.
                // OpenAI-compatible runners emit indexed argument fragments.
                val index = call["index"]?.jsonPrimitive?.intOrNull ?: run {
                    require(!call["id"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                        "Missing tool call identity"
                    }
                    val function = call["function"]?.jsonObject ?: error("Missing tool function")
                    require(!function["name"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
                    Json.parseToJsonElement(function["arguments"]!!.jsonPrimitive.content).jsonObject
                    (tools.keys.maxOrNull() ?: -1) + 1
                }
                require(index in 0..127) { "Invalid tool call index" }
                val pending = tools.getOrPut(index) { Pending() }
                call["id"]?.jsonPrimitive?.contentOrNull?.let { pending.id += it }
                call["function"]?.jsonObject?.let { function ->
                    function["name"]?.jsonPrimitive?.contentOrNull?.let { pending.name += it }
                    function["arguments"]?.jsonPrimitive?.contentOrNull?.let { pending.arguments.append(it) }
                }
            }
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
        }
        return events
    }

    private fun nativeValue(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { nativeValue(it.value) }
        is JsonArray -> value.map { nativeValue(it) }
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.boolean
            value.longOrNull != null -> value.long
            value.doubleOrNull != null -> value.double
            else -> error("Invalid JSON value")
        }
    }
}
