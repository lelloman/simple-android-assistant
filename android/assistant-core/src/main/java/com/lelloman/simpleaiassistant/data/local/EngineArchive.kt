package com.lelloman.simpleaiassistant.data.local

import androidx.room.*
import com.lelloman.simpleaiassistant.engine.AssistantHistoryStore
import com.lelloman.simpleaiassistant.engine.anyJson
import kotlinx.serialization.json.*

@Entity(tableName = "assistant_archive")
data class EngineArchiveEntity(@PrimaryKey val id: Int = 1, val json: String)
@Dao
interface EngineArchiveDao {
    @Query("SELECT json FROM assistant_archive WHERE id = 1") suspend fun load(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(archive: EngineArchiveEntity)
}
/** Imports v1 messages once. Original mode is deliberately unknown for legacy turns. */
class RoomHistoryStore(private val database: ChatDatabase, private val defaultModeId: String) : AssistantHistoryStore {
    override suspend fun load(): String? = database.withTransaction {
        database.engineArchiveDao().load() ?: database.chatMessageDao().getAll().takeIf { it.isNotEmpty() }?.let { legacy ->
            buildJsonObject {
                put("version", 1); put("modeId", defaultModeId); put("language", JsonNull)
                put("modeSnapshots", buildJsonObject {}); put("modeChanges", buildJsonArray {}); put("summary", JsonNull); put("nextId", 0)
                put("messages", JsonArray(legacy.map { entity ->
                    val m = entity.toDomain()
                    buildJsonObject {
                        put("id", m.id); put("role", m.role.name.lowercase()); put("content", m.content); put("timestamp", m.timestamp)
                        m.toolCallId?.let { put("toolCallId", it) }; m.toolName?.let { put("toolName", it) }
                        m.toolCalls?.let { calls -> put("toolCalls", JsonArray(calls.map { c -> buildJsonObject { put("id", c.id); put("name", c.name); put("input", anyJson(c.input)) } })) }
                    }
                }))
            }.toString()
        }
    }
    override suspend fun save(archive: String) = database.withTransaction {
        database.engineArchiveDao().save(EngineArchiveEntity(json = archive))
        // The snapshot now contains all imported messages; keep only one source of truth.
        database.chatMessageDao().deleteAll()
    }
}
