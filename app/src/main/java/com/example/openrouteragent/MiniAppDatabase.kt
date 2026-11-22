package com.example.openrouteragent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Lightweight index abstraction for instant launcher grid reads without scanning or parsing HTML files.
 * Production uses [MiniAppDatabase] backed by SQLiteOpenHelper (matching [ChatDatabase]); pure JVM tests
 * may supply [InMemoryMiniAppIndex].
 */
interface MiniAppIndex {
    fun list(): List<MiniApp>
    fun find(idOrName: String): MiniApp?
    fun upsert(app: MiniApp)
    fun delete(id: String)
    fun replaceAll(apps: List<MiniApp>)
}

/** Lightweight SQLiteOpenHelper index of installed mini app bundles (no Room/KSP codegen). */
class MiniAppDatabase(
    context: Context,
    dbFile: File = File(context.noBackupFilesDir, "mini_apps.db"),
) : SQLiteOpenHelper(context, dbFile.absolutePath, null, 1), MiniAppIndex {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE mini_apps (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                theme_color TEXT NOT NULL DEFAULT '$MINI_APP_DEFAULT_THEME_COLOR',
                icon_uri TEXT,
                entry_point TEXT NOT NULL DEFAULT '$MINI_APP_DEFAULT_ENTRY_POINT',
                allow_network INTEGER NOT NULL DEFAULT 1,
                imported INTEGER NOT NULL DEFAULT 0,
                version INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL("CREATE INDEX mini_apps_updated ON mini_apps(updated_at DESC, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported mini apps database upgrade from $oldVersion to $newVersion.")
    }

    override fun list(): List<MiniApp> = readableDatabase.rawQuery(
        """SELECT id,name,description,theme_color,icon_uri,entry_point,allow_network,imported,version,created_at,updated_at
           FROM mini_apps ORDER BY updated_at DESC, id""",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toMiniApp())
        }
    }

    override fun find(idOrName: String): MiniApp? {
        val db = readableDatabase
        db.rawQuery(
            """SELECT id,name,description,theme_color,icon_uri,entry_point,allow_network,imported,version,created_at,updated_at
               FROM mini_apps WHERE id=? LIMIT 1""",
            arrayOf(idOrName),
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toMiniApp()
        }
        val trimmed = idOrName.trim()
        if (trimmed.isEmpty()) return null
        return db.rawQuery(
            """SELECT id,name,description,theme_color,icon_uri,entry_point,allow_network,imported,version,created_at,updated_at
               FROM mini_apps WHERE LOWER(name)=LOWER(?) ORDER BY updated_at DESC LIMIT 1""",
            arrayOf(trimmed),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toMiniApp() else null
        }
    }

    override fun upsert(app: MiniApp) {
        writableDatabase.insertWithOnConflict(
            "mini_apps",
            null,
            app.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { check(it != -1L) { "Could not save mini app index row." } }
    }

    override fun delete(id: String) {
        writableDatabase.delete("mini_apps", "id=?", arrayOf(id))
    }

    override fun replaceAll(apps: List<MiniApp>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("mini_apps", null, null)
            for (app in apps) {
                db.insertWithOnConflict("mini_apps", null, app.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun MiniApp.toContentValues() = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("theme_color", themeColor)
        if (iconUri != null) put("icon_uri", iconUri) else putNull("icon_uri")
        put("entry_point", entryPoint)
        put("allow_network", if (allowNetwork) 1 else 0)
        put("imported", if (imported) 1 else 0)
        put("version", version)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun Cursor.toMiniApp() = MiniApp(
        id = getString(0),
        name = getString(1),
        description = getString(2),
        themeColor = getString(3),
        iconUri = if (isNull(4)) null else getString(4),
        entryPoint = getString(5),
        allowNetwork = getInt(6) != 0,
        imported = getInt(7) != 0,
        version = getInt(8),
        createdAt = getLong(9),
        updatedAt = getLong(10),
    )
}

/** Pure JVM in-memory [MiniAppIndex] used by unit tests without an Android context. */
class InMemoryMiniAppIndex : MiniAppIndex {
    private val rows = LinkedHashMap<String, MiniApp>()

    @Synchronized
    override fun list(): List<MiniApp> = rows.values.sortedWith(
        compareByDescending<MiniApp> { it.updatedAt }.thenBy { it.id },
    )

    @Synchronized
    override fun find(idOrName: String): MiniApp? =
        rows[idOrName] ?: list().firstOrNull { it.name.equals(idOrName.trim(), ignoreCase = true) }

    @Synchronized
    override fun upsert(app: MiniApp) {
        rows[app.id] = app
    }

    @Synchronized
    override fun delete(id: String) {
        rows.remove(id)
    }

    @Synchronized
    override fun replaceAll(apps: List<MiniApp>) {
        rows.clear()
        apps.forEach { rows[it.id] = it }
    }
}
