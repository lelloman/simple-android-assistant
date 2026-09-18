package com.lelloman.simpleaiassistant.engine

import com.lelloman.simpleaiassistant.diagnostics.DiagnosticRecorder
import com.lelloman.simpleaiassistant.diagnostics.DiagnosticTurnToken
import com.lelloman.simpleaiassistant.model.MessageRole
import kotlinx.serialization.json.JsonObject

/** Observes accepted engine transitions under the session lock. Never records discarded callbacks. */
internal class SessionDiagnostics(private val recorder: DiagnosticRecorder, private val providerId: String) {
    private var turn: DiagnosticTurnToken? = null
    private var iteration = 0

    suspend fun record(command: JsonObject, before: AssistantState, after: AssistantState) {
        val type = command.text("type")
        if (type == "clear") {
            recorder.clear()
            turn = null
            return
        }
        if (before == after) return
        val startsTurn = type in setOf("send", "restart", "confirm_restart") && after.activity != "idle"
        if (startsTurn || type == "cancel") {
            recorder.event(turn, "cancellation", content = "Cancelled")
            recorder.finish(turn, "cancelled")
            turn = null
        }
        if (startsTurn) {
            turn = recorder.begin(after.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty(), mapOf("provider" to providerId))
            iteration = 0
        }
        val existing = before.messages.map { it.id }.toSet()
        for (message in after.messages.filter { it.id !in existing }) {
            recorder.event(turn, "metadata", data = mapOf("message_id" to message.id, "role" to message.role.name.lowercase()))
            when (message.role) {
                MessageRole.ASSISTANT -> {
                    recorder.checkpoint(turn, message.content, iteration++, force = true)
                    message.toolCalls.orEmpty().forEach { call ->
                        recorder.event(turn, "tool_call", toolName = call.name, data = mapOf("call_id" to call.id, "arguments" to call.input))
                    }
                }
                MessageRole.TOOL -> recorder.event(turn, "tool_result", content = message.content, toolName = message.toolName, data = mapOf("call_id" to message.toolCallId))
                else -> Unit
            }
        }
        if (after.streamingText.isNotEmpty()) recorder.checkpoint(turn, after.streamingText, iteration)
        if (after.error != null && after.error != before.error) recorder.event(turn, "error", content = after.error)
        if (after.activity == "compact" && before.activity != "compact") recorder.event(turn, "compaction")
        if (after.activity == "idle") {
            recorder.finish(turn, if (after.error == null) "completed" else "failed")
            turn = null
        }
    }

    suspend fun close() {
        recorder.event(turn, "cancellation", content = "Session closed")
        recorder.finish(turn, "cancelled")
        turn = null
    }
}
