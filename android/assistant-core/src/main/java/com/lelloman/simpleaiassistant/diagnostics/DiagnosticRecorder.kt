package com.lelloman.simpleaiassistant.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Serializable internal data class DiagnosticEvent(val kind:String,val timestamp:String,val content:String?=null,val tool_name:String?=null,val data:JsonElement?=null)
@Serializable internal data class DiagnosticTurn(val id:String,val conversation_id:String,val started_at:String,var status:String="running",val events:MutableList<DiagnosticEvent> = mutableListOf())
@Serializable internal data class DiagnosticEnvelope(val schema_version:Int=1,var captured_at:String,val turns:MutableList<DiagnosticTurn> = mutableListOf())
@Serializable private data class StoredDiagnostics(val owner:String,val envelope:DiagnosticEnvelope)

/** A stale token cannot recreate a recording after clear, logout, or account switching. */
class DiagnosticTurnToken internal constructor(internal val generation:Long,val id:String)
class DiagnosticSnapshot internal constructor(val content:String,internal val generation:Long) {
    val sizeBytes get() = content.toByteArray(Charsets.UTF_8).size
}

/** Independent, opt-in best-effort recorder. No exception or raw content goes to host logs. */
class DiagnosticRecorder(private val storage:DiagnosticStorage,private val maxBytes:Int=MAX_BYTES,private val clock:()->Long=System::currentTimeMillis,private val metadata:Map<String,Any?> = emptyMap()) {
    companion object { const val MAX_BYTES=2_097_152 }
    private val mutex=Mutex()
    private var generation=0L
    private val mutableRevision=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision:kotlinx.coroutines.flow.StateFlow<Long> = mutableRevision
    private fun invalidate() {generation++;mutableRevision.value=generation}
    private var owner:String?=null
    private var enabled=false
    private var loaded=false
    private var conversation=UUID.randomUUID().toString()
    private var envelope=DiagnosticEnvelope(captured_at=timestamp())
    private val checkpoints=mutableMapOf<String,Long>()
    init {require(maxBytes in 2048..MAX_BYTES)}
    private fun timestamp():String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date(clock()))
    private suspend fun <T> safe(fallback:T,block:()->T):T = withContext(NonCancellable+Dispatchers.IO) {
        mutex.withLock {
            try {block()} catch (_:Exception) {
                // Fail closed. Invalidate snapshots/tokens and attempt to remove any old copy.
                invalidate(); enabled=false; envelope.turns.clear(); checkpoints.clear()
                runCatching {storage.clear()}
                fallback
            }
        }
    }
    suspend fun setAccount(account:String?,recordingEnabled:Boolean) = safe(Unit) {
        val identity=account?.let {MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b->"%02x".format(b) }}
        if(loaded && owner==identity && enabled==recordingEnabled) return@safe
        invalidate(); checkpoints.clear(); conversation=UUID.randomUUID().toString()
        val initial=!loaded
        loaded=true; owner=identity; enabled=recordingEnabled && identity!=null
        envelope=DiagnosticEnvelope(captured_at=timestamp())
        if(initial && enabled) {
            val stored=storage.read(maxBytes)?.let {Json.decodeFromString<StoredDiagnostics>(it)}
            if(stored!=null && stored.owner==identity && stored.envelope.schema_version==1) {
                envelope=stored.envelope
                conversation=envelope.turns.lastOrNull()?.conversation_id ?: conversation
                envelope.turns.filter {it.status=="running"}.forEach {it.status="interrupted"}
            }
        }
        if(enabled) persist() else storage.clear()
    }
    suspend fun clear() = safe(Unit) {
        invalidate(); checkpoints.clear(); conversation=UUID.randomUUID().toString()
        envelope=DiagnosticEnvelope(captured_at=timestamp()); storage.clear()
    }
    suspend fun isEnabled():Boolean = safe(false) {enabled && owner!=null}
    suspend fun isCurrent(snapshot:DiagnosticSnapshot):Boolean = safe(false) {enabled && owner!=null && snapshot.generation==generation}
    suspend fun activeTurn():DiagnosticTurnToken? = safe(null) {envelope.turns.lastOrNull {it.status=="running"}?.let {DiagnosticTurnToken(generation,it.id)}}
    suspend fun begin(user:String,metadata:Map<String,Any?> = emptyMap()):DiagnosticTurnToken? = safe(null) {
        if(!enabled || owner==null) return@safe null
        val turn=DiagnosticTurn(UUID.randomUUID().toString(),conversation,timestamp())
        turn.events+=DiagnosticEvent("user",timestamp(),DiagnosticRedactor.text(user))
        turn.events+=DiagnosticEvent("metadata",timestamp(),data=DiagnosticRedactor.value(this.metadata+metadata))
        envelope.turns+=turn
        persist()
        DiagnosticTurnToken(generation,turn.id)
    }
    suspend fun event(token:DiagnosticTurnToken?,kind:String,content:String?=null,toolName:String?=null,data:Any?=null) = safe(Unit) {
        val turn=turn(token) ?: return@safe
        require(kind in setOf("assistant","tool_call","tool_result","confirmation","error","cancellation","compaction","metadata"))
        turn.events+=DiagnosticEvent(kind,timestamp(),content?.let {DiagnosticRedactor.text(it)},toolName?.let {DiagnosticRedactor.text(it,256)},data?.let {DiagnosticRedactor.value(it)})
        persist()
    }
    suspend fun checkpoint(token:DiagnosticTurnToken?,text:String,iteration:Int,force:Boolean=false) = safe(Unit) {
        val turn=turn(token) ?: return@safe
        val key="${turn.id}:$iteration"
        if(!force && clock()-(checkpoints[key] ?: Long.MIN_VALUE/2)<500) return@safe
        checkpoints[key]=clock()
        val index=turn.events.indexOfLast {it.kind=="assistant" && it.data?.jsonObject?.get("iteration")?.jsonPrimitive?.content==iteration.toString()}
        val event=DiagnosticEvent("assistant",timestamp(),DiagnosticRedactor.text(text),data=buildJsonObject {put("iteration",iteration);put("partial",!force)})
        if(index>=0) turn.events[index]=event else turn.events+=event
        persist()
    }
    suspend fun finish(token:DiagnosticTurnToken?,status:String) = safe(Unit) {
        val turn=turn(token) ?: return@safe
        require(status in setOf("completed","failed","cancelled"))
        turn.status=status
        checkpoints.keys.removeAll {it.startsWith("${turn.id}:")}
        persist()
    }
    suspend fun snapshot(includeAll:Boolean=false,messageId:String?=null):DiagnosticSnapshot? = safe(null) {
        if(!enabled || owner==null || envelope.turns.isEmpty()) return@safe null
        val selected=if(includeAll) envelope.turns else if(messageId!=null) envelope.turns.filter {turn->turn.events.any {event->(event.data as? JsonObject)?.get("message_id")?.jsonPrimitive?.content==messageId}} else envelope.turns.filter {it.conversation_id==envelope.turns.last().conversation_id}
        if(selected.isEmpty()) return@safe null
        DiagnosticSnapshot(Json.encodeToString(DiagnosticEnvelope(captured_at=timestamp(),turns=selected.toMutableList())),generation)
    }
    private fun turn(token:DiagnosticTurnToken?):DiagnosticTurn? = if(!enabled || token==null || token.generation!=generation) null else envelope.turns.find {it.id==token.id && it.status=="running"}
    private fun persist() {
        envelope.captured_at=timestamp()
        while(envelope.turns.size>128) envelope.turns.removeAt(0)
        envelope.turns.forEach {turn->
            if(turn.events.size>512) {while(turn.events.size>511) turn.events.removeAt(1);turn.events.add(1,DiagnosticEvent("metadata",timestamp(),DiagnosticRedactor.TRUNCATED))}
        }
        fun encoded()=Json.encodeToString(StoredDiagnostics(checkNotNull(owner),envelope))
        var text=encoded()
        while(text.toByteArray(Charsets.UTF_8).size>maxBytes) {
            if(envelope.turns.size>1) envelope.turns.removeAt(0)
            else {
                val events=envelope.turns.single().events
                if(events.size>2) events.removeAt(1)
                else if(events.isNotEmpty()) {
                    val event=events.removeAt(events.lastIndex)
                    if(event.content!=null && event.content.length>32) events+=event.copy(content=DiagnosticRedactor.utf8Prefix(event.content,maxOf(32,event.content.toByteArray().size/2)),data=null)
                } else error("Diagnostic envelope exceeds bound")
                if(events.none {it.content==DiagnosticRedactor.TRUNCATED}) events.add(0,DiagnosticEvent("metadata",timestamp(),DiagnosticRedactor.TRUNCATED))
            }
            text=encoded()
        }
        storage.write(text)
    }
}
