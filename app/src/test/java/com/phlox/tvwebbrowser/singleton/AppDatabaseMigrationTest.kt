package com.phlox.tvwebbrowser.singleton

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.phlox.tvwebbrowser.model.TabEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Creates a database with an old schema exported by Room (app/schemas), migrates it
 * with the app migrations and lets Room validate the result.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    private fun createDatabase(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schemaFile = File("schemas/${AppDatabase::class.java.name}/$version.json")
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val file = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        file.parentFile!!.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        val setupQueries = schema.getJSONArray("setupQueries")
        for (i in 0 until setupQueries.length()) {
            db.execSQL(setupQueries.getString(i))
        }
        fill(db)
        db.version = version
        db.close()
    }

    private fun openMigrated(): AppDatabase {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        database = db
        return db
    }

    @Test
    fun migrate19To20DropsWebViewStateColumn() {
        createDatabase(19) { db ->
            db.insert("tabs", null, ContentValues().apply {
                put("id", 5L)
                put("url", "https://example.com/")
                put("title", "Example")
                put("selected", 1)
                put("thumbnailHash", "thumb")
                put("faviconHash", "fav")
                put("incognito", 0)
                put("position", 1)
                put("wv_state", byteArrayOf(1, 2, 3))
                put("wv_state_file", "state")
                put("adblock", 0)
                put("scale", 1.5f)
            })
            db.insert("tabs", null, ContentValues().apply {
                put("id", 7L)
                put("url", "https://incognito.com/")
                put("title", "")
                put("selected", 0)
                put("incognito", 1)
                put("position", 0)
            })
            db.insert("history", null, ContentValues().apply {
                put("id", 1L)
                put("time", 100L)
                put("title", "History")
                put("url", "https://history.com/")
            })
        }

        // Room validates the migrated schema when the database is opened
        val db = openMigrated()
        val tabs = runBlocking { db.tabsDao().getAll(incognito = false) }
        assertEquals(1, tabs.size)
        val tab = tabs[0]
        assertEquals(5L, tab.id)
        assertEquals("https://example.com/", tab.url)
        assertEquals("Example", tab.title)
        assertEquals(true, tab.selected)
        assertEquals("thumb", tab.thumbnailHash)
        assertEquals("fav", tab.faviconHash)
        assertEquals(false, tab.incognito)
        assertEquals(1, tab.position)
        assertEquals("state", tab.wvStateFileName)
        assertEquals(false, tab.adblock)
        assertEquals(1.5f, tab.scale)

        val incognitoTab = runBlocking { db.tabsDao().getAll(incognito = true) }.single()
        assertEquals(7L, incognitoTab.id)
        assertNull(incognitoTab.adblock)
        assertNull(incognitoTab.scale)

        assertEquals(1, runBlocking { db.historyDao().count() })

        val sqlDb = db.openHelper.readableDatabase
        assertEquals(20, sqlDb.version)
        sqlDb.query("PRAGMA table_info(tabs)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = ArrayList<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            assertFalse(columns.contains("wv_state"))
        }
    }

    @Test
    fun newTabsGetNewIdsAfterMigration() {
        createDatabase(19) { db ->
            db.insert("tabs", null, ContentValues().apply {
                put("id", 5L)
                put("url", "https://example.com/")
                put("title", "")
                put("selected", 0)
                put("incognito", 0)
                put("position", 0)
            })
        }
        val db = openMigrated()
        val newTab = TabEntity(url = "https://new.com/")
        val id = runBlocking { db.tabsDao().insert(newTab) }
        assertEquals(6L, id)
    }
}
