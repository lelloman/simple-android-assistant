package com.lelloman.simpleaiassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lelloman.simpleaiassistant.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RoomHistoryStoreTest {
    @Test fun migratesLegacyMessagesWithStableOrderAndUnknownMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "assistant-migration-test"
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE chat_messages (id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, toolCallsJson TEXT, toolCallId TEXT, toolName TEXT, timestamp INTEGER NOT NULL)")
        old.execSQL("INSERT INTO chat_messages VALUES ('z', 'USER', 'first', NULL, NULL, NULL, 10)")
        old.execSQL("INSERT INTO chat_messages VALUES ('a', 'ASSISTANT', 'second', NULL, NULL, NULL, 10)")
        old.version = 1; old.close()
        val db = Room.databaseBuilder(context, ChatDatabase::class.java, name).addMigrations(ChatDatabase.MIGRATION_1_2).build()
        try {
            val store = RoomHistoryStore(db, "general")
            val archive = store.load()!!
            val messages = Json.parseToJsonElement(archive).jsonObject.getValue("messages").jsonArray
            assertEquals(listOf("z", "a"), messages.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertTrue(messages.all { it.jsonObject["modeSnapshotId"] == null })
            store.save(archive)
            assertEquals(archive, store.load())
            assertEquals(0, db.chatMessageDao().count())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
