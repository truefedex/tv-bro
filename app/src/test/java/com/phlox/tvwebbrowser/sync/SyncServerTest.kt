package com.phlox.tvwebbrowser.sync

import android.content.Context
import androidx.room.Room
import com.phlox.tvwebbrowser.data.DataRepositories
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.HOME_PAGE
import com.phlox.tvwebbrowser.data.FavoritesRepository.Companion.ROOT
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.PairedDevice
import com.phlox.tvwebbrowser.singleton.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private val tvBookmarks = LocalBookmarks({ tvData }, backup, "Home page")
    private val phoneBackup = BookmarksBackup(File(context.filesDir, "phone"))
    private val phoneBookmarks = LocalBookmarks({ phoneData }, phoneBackup, "Home page")

    private val opened = Collections.synchronizedList(ArrayList<SyncTab>())
    private val paired = Collections.synchronizedList(ArrayList<PairedDevice>())
    private val replaced = Collections.synchronizedList(ArrayList<BookmarksReplacer.Changes>())
    private val forgotten = Collections.synchronizedList(ArrayList<PairedDevice>())
    /** The files the TV got: their names and where they went */
    private val files = Collections.synchronizedList(ArrayList<Pair<String, FakeFile>>())
    /** The code the TV refuses files with, null to take them */
    @Volatile private var refuseFiles: String? = null
    /** What the next file of the TV does */
    @Volatile private var nextFile: () -> FakeFile = { FakeFile() }

    private class FakeFile(
        private val failWrites: Boolean = false,
        /** What [awaitSaved] throws, null to give the name */
        private val endCode: String? = null
    ) : SyncServer.IncomingFile {
        val bytes = java.io.ByteArrayOutputStream()
        @Volatile var closed = false
        @Volatile var failed: String? = null

        override val output = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites) throw java.io.IOException("cancelled on the TV")
                bytes.write(b, off, len)
            }

            override fun close() {
                closed = true
            }
        }

        override fun fail(reason: String) {
            failed = reason
        }

        override suspend fun awaitSaved(): String {
            endCode?.let { throw SyncException(it) }
            return "saved.bin"
        }
    }

    private lateinit var server: SyncServer
    private val client = SyncClient(phoneSettings, { phoneData.pairedDevices })

    @Before
    fun setUp() {
        SyncKeyStore.setInstanceForTests(PlainSyncKeyStore())
        server = SyncServer(tvSettings, { tvData }, tvBookmarks, object : SyncServer.Host {
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

            override suspend fun receiveFile(from: PairedDevice, name: String, mimeType: String?, size: Long): SyncServer.IncomingFile {
                refuseFiles?.let { throw SyncException(it) }
                return nextFile().also { files += name to it }
            }
        }, appVersion = 1, bindAddress = InetAddress.getLoopbackAddress(),
            //the host is called on the thread of the session. Not Dispatchers.setMain: the threads of a server
            //still read Dispatchers.Main while the test resets it, which throws in resetMain
            mainDispatcher = Dispatchers.Unconfined)
        server.start()
    }

    @After
    fun tearDown() {
        server.destroy()
        backup.delete()
        phoneBackup.delete()
        SyncKeyStore.setInstanceForTests(null)
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

        assertNotNull(tvBookmarks.restore())
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
    fun thePhoneTakesChosenBookmarksOfTheTv() = runBlocking {
        val tv = pair()
        val tvFavorites = tvData.favorites
        val news = FavoriteItem().apply { title = "News" }
        tvFavorites.add(news, ROOT)
        tvFavorites.add(FavoriteItem().apply { title = "BBC"; url = "https://bbc.com/" }, news.id)
        tvFavorites.add(FavoriteItem().apply { title = "TV only"; url = "https://tv.com/" }, ROOT)
        tvFavorites.add(FavoriteItem().apply { title = "Home"; url = "https://home.com/" }, HOME_PAGE)
        val phoneFavorites = phoneData.favorites
        phoneFavorites.add(FavoriteItem().apply { title = "Phone only"; url = "https://phone.com/" }, ROOT)
        val before = phoneFavorites.exportTree("Home page")

        val tvTree = client.session(tv) { it.getBookmarks() }
        //the Home page folder comes first, with the places of its bookmarks
        val home = tvTree.children[0] as SyncBookmark.Folder
        assertTrue(home.homePage)
        assertEquals(listOf("https://home.com/"), home.children.map { (it as SyncBookmark.Link).url })

        //the phone takes the News folder only
        val selection = BookmarkSelection.of(tvTree)
        selection.toggle(selection.nodes.first { it.title == "News" })
        val tree = selection.tree()!!
        val preview = phoneBookmarks.preview(tree)
        assertEquals(1, preview.added)
        phoneBookmarks.replace(tree, preview)
        assertEquals(listOf("News", "Phone only"), phoneFavorites.children(ROOT).map { it.title })
        assertTrue(phoneFavorites.children(HOME_PAGE).isEmpty())
        //the TV is not changed
        assertFalse(backup.exists)

        assertNotNull(phoneBookmarks.restore())
        assertEquals(before, phoneFavorites.exportTree("Home page"))
    }

    @Test
    fun aFileIsSavedWholeWithASafeName() = runBlocking {
        val tv = pair()
        //more than a chunk, not a multiple of it
        val data = java.util.Random(1).let { random -> ByteArray(SyncRequests.FILE_CHUNK * 2 + 12345).also { random.nextBytes(it) } }
        val progress = ArrayList<Long>()
        val name = client.session(tv) { session ->
            assertTrue(session.supports(SyncRequests.SEND_FILE))
            val name = session.sendFile("../../evil/photo.jpg", "image/jpeg", data.size.toLong(),
                java.io.ByteArrayInputStream(data)) { progress += it }
            //the session goes on after a file
            session.openTabs(listOf(SyncTab("https://a.com/", "A")))
            name
        }
        assertEquals("saved.bin", name)
        assertEquals(1, files.size)
        val (savedAs, file) = files[0]
        assertEquals("photo.jpg", savedAs)
        assertTrue(data.contentEquals(file.bytes.toByteArray()))
        assertTrue(file.closed)
        assertNull(file.failed)
        assertEquals(data.size.toLong(), progress.last())
        assertEquals(1, opened.size)
    }

    @Test
    fun aFileOfUnknownSizeIsSaved() = runBlocking {
        val tv = pair()
        val name = client.session(tv) { it.sendFile("empty.txt", null, -1, java.io.ByteArrayInputStream(ByteArray(0))) {} }
        assertEquals("saved.bin", name)
        assertTrue(files[0].second.closed)
    }

    @Test
    fun aTvThatCanNotSaveFilesNowTellsWhy() = runBlocking {
        val tv = pair()
        refuseFiles = SyncRequests.NEED_PERMISSION
        val code = client.session(tv) { session ->
            val code = try {
                session.sendFile("a.jpg", null, 3, java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))) {}
                null
            } catch (e: SyncException) {
                e.code
            }
            //nothing was sent: the session goes on
            session.openTabs(listOf(SyncTab("https://a.com/", "A")))
            code
        }
        assertEquals(SyncRequests.NEED_PERMISSION, code)
        assertTrue(files.isEmpty())
        assertEquals(1, opened.size)
    }

    @Test
    fun aFileCancelledOnTheTvStopsThePhone() {
        val tv = pair()
        nextFile = { FakeFile(failWrites = true, endCode = SyncRequests.CANCELLED) }
        val data = ByteArray(SyncRequests.FILE_CHUNK * 20)
        val result = code { client.session(tv) { it.sendFile("big.mp4", "video/mp4", data.size.toLong(), java.io.ByteArrayInputStream(data)) {} } }
        assertEquals(SyncRequests.CANCELLED, result)
        assertNotNull(files[0].second.failed)
    }

    @Test
    fun aFileCutByThePhoneIsDropped() = runBlocking {
        val tv = pair()
        val data = ByteArray(SyncRequests.FILE_CHUNK * 3)
        val failing = object : java.io.InputStream() {
            var read = 0
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read >= data.size / 2) throw java.io.IOException("the file is gone")
                val count = minOf(len, data.size - read)
                read += count
                return count
            }
        }
        val failed = try {
            client.session(tv) { it.sendFile("a.bin", null, data.size.toLong(), failing) {} }
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue(failed)
        waitFor { files.isNotEmpty() && files[0].second.failed != null }
        assertNotNull(files[0].second.failed)
        assertFalse(files[0].second.closed)
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
    fun destroyingTheServerEndsItsSessions() = runBlocking {
        val tv = pair()
        val (failure, millis) = client.session(tv) { session ->
            session.openTabs(listOf(SyncTab("https://a.com/", "A")))
            server.destroy()
            val start = System.currentTimeMillis()
            val failure = try {
                session.openTabs(listOf(SyncTab("https://b.com/", "B")))
                null
            } catch (e: java.io.IOException) {
                e
            }
            failure to System.currentTimeMillis() - start
        }
        //the TV closed the connection: no answer (an error of a request is a SyncException), and no wait
        //for the read timeout of the phone
        assertNotNull(failure)
        assertFalse(failure is SyncException)
        assertTrue("took $millis ms", millis < 5000)
        assertEquals(listOf("https://a.com/"), opened.map { it.url })
    }

    @Test
    fun aTvThatIsNotThereIsUnreachable() = runBlocking {
        val tv = pair()
        server.stop()
        assertEquals(SyncClient.UNREACHABLE, code { client.session(tv) {} })
    }
}
