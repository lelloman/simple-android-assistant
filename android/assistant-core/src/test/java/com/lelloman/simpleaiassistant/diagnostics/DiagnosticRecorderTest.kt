package com.lelloman.simpleaiassistant.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecorderTest {
    private class MemoryStorage:DiagnosticStorage {
        var content:String?=null
        var writes=0
        override fun read(maxBytes:Int)=content
        override fun write(content:String) {this.content=content;writes++}
        override fun clear() {content=null}
    }
    @Test fun `bounded Unicode snapshots redact before persistence and reject stale generations`() = runBlocking {
        val storage=MemoryStorage()
        val recorder=DiagnosticRecorder(storage,4096)
        recorder.setAccount("server-user",true)
        val token=recorder.begin("hello Bearer secret-value",mapOf("access_token" to "private-token"))
        recorder.event(token,"tool_call",data=mapOf("password" to "private-password","nested" to mapOf("authorization" to "private-header")))
        repeat(12) {recorder.checkpoint(token,"🌍".repeat(4000),it,true)}
        val snapshot=recorder.snapshot()!!
        assertTrue(snapshot.sizeBytes<=4096)
        assertTrue(storage.content!!.toByteArray().size<=4096)
        assertFalse(storage.content!!.contains("private-"))
        assertFalse(storage.content!!.contains("secret-value"))
        assertFalse(snapshot.content.contains("�"))
        assertTrue(snapshot.content.contains("truncated"))
        recorder.clear()
        recorder.event(token,"assistant",content="stale")
        assertNull(recorder.snapshot())
        assertFalse(recorder.isCurrent(snapshot))
        assertNull(storage.content)
    }
    @Test fun `partial checkpoints recover interrupted and account switch clears`() = runBlocking {
        val storage=MemoryStorage()
        var now=1000L
        val recorder=DiagnosticRecorder(storage,clock={now})
        recorder.setAccount("a",true)
        val token=recorder.begin("question")
        recorder.checkpoint(token,"partial",0)
        val writes=storage.writes
        recorder.checkpoint(token,"more",0)
        assertEquals(writes,storage.writes)
        now+=600
        recorder.checkpoint(token,"more",0)
        val recovered=DiagnosticRecorder(storage,clock={now})
        recovered.setAccount("a",true)
        val snapshot=recovered.snapshot()!!.content
        assertTrue(snapshot.contains("interrupted"))
        assertTrue(snapshot.contains("more"))
        recovered.setAccount("b",true)
        assertNull(recovered.snapshot())
        recovered.setAccount("b",false)
        assertNull(storage.content)
    }
    @Test fun `eviction removes whole old turns and snapshots are immutable`() = runBlocking {
        val recorder=DiagnosticRecorder(MemoryStorage(),4096)
        recorder.setAccount("a",true)
        val first=recorder.begin("first")
        recorder.finish(first,"completed")
        val snapshot=recorder.snapshot()!!
        repeat(10) {val t=recorder.begin("new-$it "+"é".repeat(900));recorder.finish(t,"cancelled")}
        assertTrue(snapshot.content.contains("first"))
        assertFalse(recorder.snapshot()!!.content.contains("first"))
        assertTrue(recorder.isCurrent(snapshot))
    }
    @Test fun `storage failure is non fatal and disables collection`() = runBlocking {
        val broken=object:DiagnosticStorage {
            override fun read(maxBytes:Int):String?=null
            override fun write(content:String) {error("disk failed")}
            override fun clear() {error("disk failed")}
        }
        val recorder=DiagnosticRecorder(broken)
        recorder.setAccount("a",true)
        assertNull(recorder.begin("secret"))
        assertFalse(recorder.isEnabled())
        assertNull(recorder.snapshot())
    }
    @Test fun `redactor removes structured and recognizable credentials`() {
        val value=DiagnosticRedactor.value(mapOf("tool" to "{\"refresh_token\":\"hidden\"}","text" to "Bearer abcdef sk-abcdefghijk eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature"))
        assertFalse(value.toString().contains("hidden"))
        assertFalse(value.toString().contains("abcdef"))
        assertFalse(value.toString().contains("signature"))
    }
}
