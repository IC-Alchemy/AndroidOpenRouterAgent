package com.example.openrouteragent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal data class ChatScope(val provider: String, val account: String) {
    val args get() = arrayOf(provider, account)
}

internal data class ChatProject(val id: String, val name: String)
internal data class SavedChat(val id: String, val title: String, val projectId: String?, val updatedAt: Long)
internal data class ChatLibrary(val chats: List<SavedChat> = emptyList(), val projects: List<ChatProject> = emptyList())

internal class ChatDatabase(context: Context) : SQLiteOpenHelper(
    context, File(context.noBackupFilesDir, "conversations.db").absolutePath, null, 1,
) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE chats (
            id TEXT PRIMARY KEY NOT NULL, provider TEXT NOT NULL, account TEXT NOT NULL, title TEXT NOT NULL,
            project_id TEXT REFERENCES projects(id) ON DELETE SET NULL, updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE legacy_chats (
            provider TEXT NOT NULL, account TEXT NOT NULL, PRIMARY KEY(provider, account)
        )""")
        db.execSQL("CREATE INDEX chats_scope ON chats(provider, account, updated_at DESC)")
        db.execSQL("""CREATE TABLE chat_parts (
            chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE, part INTEGER NOT NULL, bytes BLOB NOT NULL,
            PRIMARY KEY(chat_id, part)
        )""")
        db.execSQL("""CREATE TABLE active_chats (
            provider TEXT NOT NULL, account TEXT NOT NULL, chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            PRIMARY KEY(provider, account)
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported chat database upgrade from $oldVersion to $newVersion.")
    }

    fun library(scope: ChatScope): ChatLibrary {
        val db = readableDatabase
        val chats = db.rawQuery(
            "SELECT id,title,project_id,updated_at FROM chats WHERE provider=? AND account=? ORDER BY updated_at DESC,id",
            scope.args,
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) add(SavedChat(rows.getString(0), rows.getString(1), rows.getString(2), rows.getLong(3)))
            }
        }
        val projects = db.rawQuery("SELECT id,name FROM projects ORDER BY name COLLATE NOCASE,id", null).use { rows ->
            buildList { while (rows.moveToNext()) add(ChatProject(rows.getString(0), rows.getString(1))) }
        }
        return ChatLibrary(chats, projects)
    }

    fun importLegacy(scope: ChatScope, read: () -> Workspace?): Boolean = transaction {
        val alreadyImported = rawQuery("SELECT 1 FROM legacy_chats WHERE provider=? AND account=?", scope.args).use {
            it.moveToFirst()
        }
        if (alreadyImported) return@transaction false
        val old = read() ?: return@transaction false
        if (old.accountTag != scope.account) return@transaction false
        if (old.messages.isNotEmpty() || old.containerId != null || old.sdkHistory != null) save(scope, old)
        insertOrThrow("legacy_chats", null, ContentValues().apply {
            put("provider", scope.provider)
            put("account", scope.account)
        })
        true
    }

    fun active(scope: ChatScope): Workspace? = transaction {
        val id = rawQuery("SELECT chat_id FROM active_chats WHERE provider=? AND account=?", scope.args).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: rawQuery("SELECT id FROM chats WHERE provider=? AND account=? ORDER BY updated_at DESC,id LIMIT 1",
            scope.args).use { if (it.moveToFirst()) it.getString(0) else null }
        id?.let { loadChat(scope, it) }
    }

    fun open(scope: ChatScope, id: String): Workspace = transaction {
        requireNotNull(loadChat(scope, id)) { "This chat is unavailable." }.also { setActive(scope, id) }
    }

    fun save(scope: ChatScope, workspace: Workspace, projectId: String? = null) {
        require(scope.account == workspace.accountTag)
        val bytes = json.encodeToString(Workspace.serializer(), workspace).toByteArray()
        transaction {
            val currentTitle = rawQuery("SELECT title FROM chats WHERE provider=? AND account=? AND id=?",
                scope.args + workspace.id).use { if (it.moveToFirst()) it.getString(0) else null }
            val title = chatTitle(workspace)
            val metadata = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (currentTitle == null || currentTitle.isEmpty() && title.isNotEmpty()) put("title", title)
            }
            if (currentTitle == null) {
                metadata.put("id", workspace.id)
                metadata.put("provider", scope.provider)
                metadata.put("account", scope.account)
                metadata.put("project_id", projectId)
                insertOrThrow("chats", null, metadata)
            } else {
                update("chats", metadata, "provider=? AND account=? AND id=?", scope.args + workspace.id)
            }
            // CursorWindow cannot return a row larger than its buffer. Large SDK histories span rows.
            delete("chat_parts", "chat_id=?", arrayOf(workspace.id))
            for ((part, offset) in (0 until bytes.size step 262_144).withIndex()) {
                insertOrThrow("chat_parts", null, ContentValues().apply {
                    put("chat_id", workspace.id)
                    put("part", part)
                    put("bytes", bytes.copyOfRange(offset, minOf(offset + 262_144, bytes.size)))
                })
            }
            setActive(scope, workspace.id)
        }
    }

    fun renameChat(scope: ChatScope, id: String, name: String) = editChat(scope, id, ContentValues().apply {
        put("title", libraryName(name))
    })

    fun moveChat(scope: ChatScope, id: String, projectId: String?) = editChat(scope, id, ContentValues().apply {
        put("project_id", projectId)
    })

    fun deleteChat(scope: ChatScope, id: String) {
        writableDatabase.delete("chats", "provider=? AND account=? AND id=?", scope.args + id)
    }

    fun deleteProvider(provider: String) {
        writableDatabase.delete("chats", "provider=?", arrayOf(provider))
    }

    fun addProject(name: String) {
        writableDatabase.insertOrThrow("projects", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("name", libraryName(name))
        })
    }

    fun renameProject(id: String, name: String) {
        writableDatabase.update("projects", ContentValues().apply { put("name", libraryName(name)) }, "id=?", arrayOf(id))
    }

    fun deleteProject(id: String) {
        writableDatabase.delete("projects", "id=?", arrayOf(id))
    }

    private fun editChat(scope: ChatScope, id: String, changes: ContentValues) {
        check(writableDatabase.update("chats", changes, "provider=? AND account=? AND id=?", scope.args + id) == 1)
    }

    private fun SQLiteDatabase.loadChat(scope: ChatScope, id: String): Workspace? {
        val bytes = ByteArrayOutputStream()
        rawQuery("""SELECT p.bytes FROM chat_parts p JOIN chats c ON c.id=p.chat_id
            WHERE c.provider=? AND c.account=? AND c.id=? ORDER BY p.part""", scope.args + id).use { rows ->
            while (rows.moveToNext()) bytes.write(rows.getBlob(0))
        }
        if (bytes.size() == 0) return null
        return json.decodeFromString<Workspace>(bytes.toString(Charsets.UTF_8.name()))
    }

    private fun SQLiteDatabase.setActive(scope: ChatScope, id: String) {
        insertWithOnConflict("active_chats", null, ContentValues().apply {
            put("provider", scope.provider)
            put("account", scope.account)
            put("chat_id", id)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    private inline fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}

internal fun chatTitle(workspace: Workspace): String = workspace.messages.firstOrNull { it.isUser && it.text.isNotBlank() }
    ?.text.orEmpty().ifBlank { workspace.interruptedTurn?.prompt.orEmpty() }.trim().replace(Regex("\\s+"), " ").take(80)

internal fun libraryName(input: String): String = input.trim().also {
    require(it.isNotEmpty() && it.length <= 80) { "Enter a name between 1 and 80 characters." }
}
