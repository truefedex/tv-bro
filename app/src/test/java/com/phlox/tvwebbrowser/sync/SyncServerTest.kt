package com.phlox.tvwebbrowser.sync

import android.content.Context
import androidx.room.Room
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.InetAddress
import java.util.Collections

/**
 * A TV and a phone in one process: each has its own database and settings, they talk over the loopback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncServerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val tvDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val phoneDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val tvData = DataRepositories(tvDb)
    private val phoneData = DataRepositories(phoneDb)
    private val tvSettings = SyncSettings(context, context.getSharedPreferences("tv", Context.MODE_PRIVATE))
    private val phoneSettings = SyncSettings(context, context.getSharedPreferences("phone", Context.MODE_PRIVATE))
    private val backup = BookmarksBackup(File(context.filesDir, "tv"))

    private val opened = Collections.synchronizedList(ArrayList<SyncTab>())
    private val paired = Collections.synchronizedList(ArrayList<PairedDevice>())
    private val replaced = Collections.synchronizedList(ArrayList<BookmarksReplacer.Changes>())
    private val forgotten = Collections.synchronizedList(ArrayList<PairedDevice>())

    private lateinit var server: SyncServer
    private val client = SyncClient(phoneSettings, { phoneData.pairedDevices })

    @Before
    fun setUp() {
        //the server calls its host on the main thread: here on the thread of the session
        Dispatchers.setMain(Dispatchers.Unconfined)
        SyncKeyStore.setInstanceForTests(PlainSyncKeyStore())
        server = SyncServer(tvSettings, { tvData }, backup, "Home page", object : SyncServer.Host {
            override fun openTabs(from: PairedDevice, tabs: List<SyncTab>) {
                opened += tabs
            }

            override fun onBookmarksReplaced(from: PairedDevice, changes: BookmarksReplacer.Changes) {
                replaced += changes
            }

            override fun onPaired(device: PairedDevice) {
                paired += device
            }

            override fun onForgotten(device: PairedDevice) {
                forgotten += device
            }
        }, appVersion = 1, bindAddress = InetAddress.getLoopbackAddress())
        server.start()
    }

    @After
    fun tearDown() {
        server.destroy()
        backup.delete()
        SyncKeyStore.setInstanceForTests(null)
        Dispatchers.resetMain()
        tvDb.close()
        phoneDb.close()
    }

    /** The link of the QR code, with the loopback as the address */
    private fun pairingLink(): SyncLink = runBlocking {
        server.startPairing()
        val link = withTimeout(5000) { server.pairingLink.filterNotNull().first() }
        link.copy(addresses = listOf("127.0.0.1"))
    }

    private fun pair(): PairedDevice = runBlocking { client.pair(pairingLink()) }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun code(block: suspend () -> Unit): String? = runBlocking {
        try {
            block()
            null
        } catch (e: SyncException) {
            e.code
        }
    }

    @Test
    fun aPhonePairsAndOpensTabs() = runBlocking {
        val tv = pair()
        assertEquals(PairedDevice.KIND_TV, tv.kind)
        assertEquals(tvSettings.deviceId, tv.peerId)
        assertEquals(server.port, tv.port)
        val phone = tvData.pairedDevices.findByPairingId(tv.pairingId)!!
        assertEquals(PairedDevice.KIND_PHONE, phone.kind)
        assertEquals(phoneSettings.deviceId, phone.peerId)
        //the TV is told after the phone got its answer
        waitFor { paired.isNotEmpty() }
        assertEquals(listOf(phone.pairingId), paired.map { it.pairingId })

        val count = client.session(tv) { session ->
            assertTrue(session.supports(SyncRequests.OPEN_TABS))
            session.openTabs(listOf(SyncTab("https://a.com/", "A"), SyncTab("javascript:alert(1)", "bad"),
                SyncTab("https://b.com/", "B")))
        }
        assertEquals(2, count)
        assertEquals(listOf("https://a.com/", "https://b.com/"), opened.map { it.url })
    }

    @Test
    fun theCodeOfAPairingIsUsedOnce() {
        val link = pairingLink()
        runBlocking { client.pair(link) }
        //the TV has a new code: it does not understand the phone, and closes the connection
        val failed = runBlocking {
            try {
                client.pair(link)
                false
            } catch (e: java.io.IOException) {
                true
            }
        }
        assertTrue(failed)
        waitFor { paired.isNotEmpty() }
        assertEquals(1, paired.size)
    }

    @Test
    fun noPairingWhileTheCodeIsNotShown() {
        val link = pairingLink()
        server.stopPairing()
        assertEquals(SyncChannel.PAIRING_CLOSED, code { client.pair(link) })
        assertTrue(paired.isEmpty())
    }

    @Test
    fun bookmarksAreReplacedAfterAPreviewAndCanBeRestored() = runBlocking {
        val tv = pair()
        val favorites = tvData.favorites
        favorites.add(FavoriteItem().apply { title = "Old"; url = "https://old.com/" }, ROOT)
        val before = favorites.exportTree("Home page")
        val tree = SyncBookmark.Folder("", all = true, children = listOf(SyncBookmark.Link("New", "https://new.com/")))

        val changes = client.session(tv) { session ->
            val preview = session.previewBookmarks(tree)
            assertEquals(1, preview.added)
            assertEquals(1, preview.removed)
            assertEquals(listOf("Old"), preview.removedTitles)
            //the preview changes nothing
            assertEquals(before, runBlocking { favorites.exportTree("Home page") })
            session.replaceBookmarks(tree, preview)
        }
        assertEquals(1, changes.added)
        assertEquals(listOf("New"), favorites.children(ROOT).map { it.title })
        assertEquals(1, replaced.size)
        assertTrue(backup.exists)

        assertNotNull(server.restoreBookmarks())
        assertEquals(before, favorites.exportTree("Home page"))
        assertFalse(backup.exists)
    }

    @Test
    fun bookmarksAreNotReplacedIfTheTvChangedAfterThePreview() = runBlocking {
        val tv = pair()
        val tree = SyncBookmark.Folder("", all = true, children = listOf(SyncBookmark.Link("New", "https://new.com/")))
        val result = client.session(tv) { session ->
            val preview = session.previewBookmarks(tree)
            runBlocking { tvData.favorites.add(FavoriteItem().apply { title = "Late"; url = "https://late.com/" }, ROOT) }
            try {
                session.replaceBookmarks(tree, preview)
                null
            } catch (e: SyncException) {
                e.code
            }
        }
        assertEquals(SyncRequests.CHANGED, result)
        assertEquals(listOf("Late"), tvData.favorites.children(ROOT).map { it.title })
        assertFalse(backup.exists)
    }

    @Test
    fun aForgottenPhoneCanNotConnect() = runBlocking {
        val tv = pair()
        client.session(tv) { it.forget() }
        assertEquals(1, forgotten.size)
        assertNull(tvData.pairedDevices.findByPairingId(tv.pairingId))
        assertEquals(SyncChannel.UNKNOWN_PAIRING, code { client.session(tv) {} })
    }

    @Test
    fun aTvThatIsNotThereIsUnreachable() = runBlocking {
        val tv = pair()
        server.stop()
        assertEquals(SyncClient.UNREACHABLE, code { client.session(tv) {} })
    }
}
