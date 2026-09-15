package com.lelloman.simpleaiassistant.engine

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConformanceTest {
    @Test fun sharedProtocolFixture() {
        val fixture = Json.parseToJsonElement(File(System.getProperty("assistant.fixtures"), "conformance.json").readText()).jsonObject
        val handle = NativeEngine.create(fixture.getValue("config").toString(), fixture.getValue("sessionId").jsonPrimitive.content, "")
        try {
            fixture.getValue("steps").jsonArray.forEach { step ->
                val actual = Json.parseToJsonElement(NativeEngine.dispatch(handle, step.jsonObject.getValue("command").toString()))
                subset(actual, step.jsonObject.getValue("expected"))
            }
        } finally { NativeEngine.destroy(handle) }
        try { NativeEngine.dispatch(handle, "{\"type\":\"snapshot\"}"); fail("Closed handle accepted") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("closed")) }
    }
    private fun subset(actual: JsonElement, expected: JsonElement) {
        when(expected) {
            is JsonObject -> expected.forEach { (key,value) -> subset(actual.jsonObject.getValue(key), value) }
            is JsonArray -> { assertEquals(expected.size, actual.jsonArray.size); expected.forEachIndexed { i,value -> subset(actual.jsonArray[i],value) } }
            else -> assertEquals(expected, actual)
        }
    }
}
