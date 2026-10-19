package com.phlox.tvwebbrowser.singleton

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.model.TabEntity
import kotlinx.coroutines.flow.first
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
        assertEquals(false, tab.incognito)
        assertEquals(1, tab.position)
        assertEquals("state", tab.wvStateFileName)

        val incognitoTab = runBlocking { db.tabsDao().getAll(incognito = true) }.single()
        assertEquals(7L, incognitoTab.id)

        assertEquals(1, runBlocking { db.historyDao().count() })

        val sqlDb = db.openHelper.readableDatabase
        assertEquals(25, sqlDb.version)
        sqlDb.query("PRAGMA table_info(tabs)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = ArrayList<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            assertFalse(columns.contains("wv_state"))
        }
    }

    @Test
    fun migrate20To21DropsUnusedTabColumns() {
        createDatabase(20) { db ->
            db.insert("tabs", null, ContentValues().apply {
                put("id", 3L)
                put("url", "https://example.com/")
                put("title", "Example")
                put("selected", 1)
                put("thumbnailHash", "thumb.webp")
                put("faviconHash", "fav")
                put("incognito", 0)
                put("position", 2)
                put("wv_state_file", "gecko:state")
                put("adblock", 1)
                put("scale", 1.5f)
            })
        }

        val db = openMigrated()
        val tab = runBlocking { db.tabsDao().getAll(incognito = false) }.single()
        assertEquals(TabEntity(id = 3L, url = "https://example.com/", title = "Example", selected = true,
            thumbnailHash = "thumb.webp", incognito = false, position = 2, wvStateFileName = "gecko:state"), tab)

        db.openHelper.readableDatabase.query("PRAGMA table_info(tabs)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = ArrayList<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            assertFalse(columns.contains("faviconHash"))
            assertFalse(columns.contains("scale"))
        }
    }

    @Test
    fun migrate21To22DropsUnusedHistoryColumns() {
        createDatabase(21) { db ->
            db.insert("history", null, ContentValues().apply {
                put("id", 4L)
                put("time", 100L)
                put("title", "Example")
                put("url", "https://example.com/")
                put("favicon", "fav")
                put("incognito", 0)
            })
        }

        val db = openMigrated()
        val item = runBlocking { db.historyDao().getAll() }.single()
        assertEquals(4L, item.id)
        assertEquals(100L, item.time)
        assertEquals("Example", item.title)
        assertEquals("https://example.com/", item.url)
        assertEquals(listOf("https://example.com/"), runBlocking { db.historyDao().frequentlyUsedUrls() }.map { it.url })

        db.openHelper.readableDatabase.query("PRAGMA table_info(history)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = ArrayList<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            assertEquals(listOf("id", "time", "title", "url"), columns)
        }
    }

    @Test
    fun migrate22To23MovesAdBlockingFromTabsToHosts() {
        createDatabase(22) { db ->
            db.insert("tabs", null, ContentValues().apply {
                put("id", 2L)
                put("url", "https://example.com/")
                put("title", "Example")
                put("selected", 1)
                put("thumbnailHash", "thumb.webp")
                put("incognito", 0)
                put("position", 0)
                put("wv_state_file", "state")
                put("adblock", 0)
            })
            db.insert("hosts", null, ContentValues().apply {
                put("id", 1L)
                put("host_name", "example.com")
                put("popup_block_level", 0)
                put("favicon", "example.png")
            })
        }

        val db = openMigrated()
        val tab = runBlocking { db.tabsDao().getAll(incognito = false) }.single()
        assertEquals(TabEntity(id = 2L, url = "https://example.com/", title = "Example", selected = true,
            thumbnailHash = "thumb.webp", incognito = false, position = 0, wvStateFileName = "state"), tab)

        //the setting of the tab is not carried over to its host
        val host = runBlocking { db.hostsDao().findByHostName("example.com") }!!
        assertNull(host.adblock)
        assertEquals(0, host.popupBlockLevel)
        assertEquals("example.png", host.favicon)
        assertEquals(0, runBlocking { db.hostsDao().adBlockSettings() }.size)

        db.openHelper.readableDatabase.query("PRAGMA table_info(tabs)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val columns = ArrayList<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
            assertFalse(columns.contains("adblock"))
        }
    }

    @Test
    fun migrate23To24GivesDownloadsTheirStatus() {
        createDatabase(23) { db ->
            fun download(id: Long, size: Long, bytesReceived: Long) =
                db.insert("downloads", null, ContentValues().apply {
                    put("id", id)
                    put("time", 1000L + id)
                    put("filename", "file$id.zip")
                    put("filepath", "/sdcard/Download/file$id.zip")
                    put("url", "https://example.com/file$id.zip")
                    put("size", size)
                    put("bytes_received", bytesReceived)
                })
            download(1, 100, 100)
            download(2, -2, 40)
            download(3, -3, 0)
            //bookmarks exported to Downloads have no url
            db.insert("downloads", null, ContentValues().apply {
                put("id", 4L)
                put("time", 2000L)
                put("filename", "bookmarks.html")
                put("filepath", "content://media/external/downloads/4")
                put("url", "")
                put("size", 10L)
                put("bytes_received", 10L)
            })
        }

        val db = openMigrated()
        val dao = db.downloadDao()
        val done = runBlocking { dao.get(1) }!!
        assertEquals(Download.Status.DONE, done.status)
        assertEquals(100L, done.size)
        assertEquals(100L, done.bytesReceived)
        assertFalse(done.resumable)
        assertNull(done.referer)
        assertNull(done.mimeType)

        val failed = runBlocking { dao.get(2) }!!
        assertEquals(Download.Status.FAILED, failed.status)
        assertEquals(0L, failed.size)
        assertEquals(40L, failed.bytesReceived)

        val cancelled = runBlocking { dao.get(3) }!!
        assertEquals(Download.Status.CANCELLED, cancelled.status)
        assertEquals(0L, cancelled.size)

        assertEquals(Download.Status.DONE, runBlocking { dao.get(4) }!!.status)
        assertEquals(listOf(4L, 3L, 2L, 1L), runBlocking { dao.observeAll().first() }.map { it.id })
    }

    @Test
    fun migrate24To25AddsPairedDevices() {
        createDatabase(24) { db ->
            db.insert("history", null, ContentValues().apply {
                put("id", 1L)
                put("time", 100L)
                put("title", "History")
                put("url", "https://history.com/")
            })
        }

        val db = openMigrated()
        assertEquals(1, runBlocking { db.historyDao().count() })
        val dao = db.pairedDevicesDao()
        val device = PairedDevice(pairingId = "p1", peerId = "phone", kind = PairedDevice.KIND_PHONE, name = "Phone",
            wrappedKey = "key", createdAt = 10, lastSeenAt = 20)
        val id = runBlocking { dao.insert(device) }
        assertEquals(device.copy(id = id), runBlocking { dao.findByPairingId("p1") })
        assertEquals(listOf(id), runBlocking { dao.observe(PairedDevice.KIND_PHONE).first() }.map { it.id })
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
