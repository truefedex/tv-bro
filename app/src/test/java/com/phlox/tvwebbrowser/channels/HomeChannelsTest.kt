package com.phlox.tvwebbrowser.channels

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.PreviewPrograms
import com.phlox.tvwebbrowser.AppContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [30])
class HomeChannelsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val config get() = AppContext.provideConfig()
    private lateinit var provider: FakeTvProvider
    private val programs = HashMap<ChannelKind, List<ChannelProgram>>()

    private class FakeSource(private val programs: () -> List<ChannelProgram>) : ChannelSource {
        override fun appLink(context: Context) = Intent("open")
        override suspend fun programs(context: Context) = programs()
    }

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeTvProvider::class.java, TvContract.AUTHORITY)
        HomeChannels.supportedForTests = true
        HomeChannels.sourcesFactory = { ChannelKind.entries.associateWith { kind -> FakeSource { programs[kind].orEmpty() } } }
        config.channelBookmarks = false
        config.channelTabs = false
        config.channelDownloads = false
    }

    @After
    fun tearDown() {
        HomeChannels.supportedForTests = null
        HomeChannels.sourcesFactory = HomeChannels.defaultSourcesFactory
    }

    private fun program(key: String, title: String = key, poster: String? = "content://posters/$key.webp") =
        ChannelProgram(key, title, "about $key", poster, "intent:$key")

    private fun sync(vararg kinds: ChannelKind, mode: HomeChannels.Mode = HomeChannels.Mode.FULL) = runBlocking {
        HomeChannels.sync(context, if (kinds.isEmpty()) ChannelKind.entries else kinds.toList(), mode)
    }

    private fun titlesOf(kind: ChannelKind): List<String> {
        val channel = provider.channelsOf(kind.id).single()
        return provider.programsOf(channel.getAsLong("_id")).map { it.getAsString(PreviewPrograms.COLUMN_TITLE) }
    }

    @Test
    fun anEnabledChannelIsCreatedWithItsProgramsInOrder() {
        config.channelBookmarks = true
        programs[ChannelKind.BOOKMARKS] = listOf(program("a"), program("b"), program("c"))
        val ids = sync()
        assertEquals(setOf(ChannelKind.BOOKMARKS), ids.keys)
        val channel = provider.channelsOf("bookmarks").single()
        assertEquals(Channels.TYPE_PREVIEW, channel.getAsString(Channels.COLUMN_TYPE))
        assertEquals("intent:a", provider.programsOf(ids[ChannelKind.BOOKMARKS]!!).first()
            .getAsString(PreviewPrograms.COLUMN_INTENT_URI))
        assertEquals(listOf("a", "b", "c"), titlesOf(ChannelKind.BOOKMARKS))
        assertEquals(1, provider.logoWrites)
        assertTrue(provider.channelsOf("tabs").isEmpty())
    }

    @Test
    fun anUnchangedSyncWritesNothing() {
        config.channelTabs = true
        programs[ChannelKind.TABS] = listOf(program("a"), program("b"))
        sync()
        provider.resetCounts()
        sync()
        assertEquals(0, provider.inserts)
        assertEquals(0, provider.updates)
        assertEquals(0, provider.deletes)
    }

    @Test
    fun onlyChangedProgramsAreUpdatedAndGoneOnesDeleted() {
        config.channelTabs = true
        programs[ChannelKind.TABS] = listOf(program("a"), program("b"), program("c"))
        sync()
        provider.resetCounts()
        programs[ChannelKind.TABS] = listOf(program("a"), program("c", poster = "content://posters/c2.webp"), program("d"))
        sync()
        assertEquals(1, provider.inserts)
        //c changed its poster and its place (weight)
        assertEquals(1, provider.updates)
        assertEquals(1, provider.deletes)
        assertEquals(listOf("a", "c", "d"), titlesOf(ChannelKind.TABS))
    }

    @Test
    fun aProgramAddedAtTheEndChangesNoOther() {
        config.channelTabs = true
        programs[ChannelKind.TABS] = listOf(program("a"), program("b"))
        sync()
        provider.resetCounts()
        programs[ChannelKind.TABS] = listOf(program("a"), program("b"), program("c"))
        sync()
        assertEquals(1, provider.inserts)
        assertEquals(0, provider.updates)
        assertEquals(listOf("a", "b", "c"), titlesOf(ChannelKind.TABS))
    }

    @Test
    fun aDisabledChannelIsDeletedAlsoWithoutAnythingInPrefs() {
        config.channelDownloads = true
        programs[ChannelKind.DOWNLOADS] = listOf(program("a"))
        sync()
        //what cleared app data would leave: the setting off, the channel still there
        config.channelDownloads = false
        sync()
        assertTrue(provider.channelsOf("downloads").isEmpty())
        assertTrue(provider.programs.isEmpty())
    }

    @Test
    fun duplicateChannelsAndProgramsAreCollapsed() {
        config.channelBookmarks = true
        programs[ChannelKind.BOOKMARKS] = listOf(program("a"))
        sync()
        val publisher = ChannelPublisher(context.contentResolver)
        val second = publisher.create(ChannelKind.BOOKMARKS, "Bookmarks", "intent:open", null)!!
        publisher.publish(second, PreviewPrograms.ASPECT_RATIO_1_1, listOf(program("x")))
        val first = provider.channelsOf("bookmarks").first().getAsLong("_id")
        //a program written twice by an interrupted sync
        context.contentResolver.insert(PreviewPrograms.CONTENT_URI, android.content.ContentValues().apply {
            put(PreviewPrograms.COLUMN_CHANNEL_ID, first)
            put(PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, "a")
            put(PreviewPrograms.COLUMN_TITLE, "a")
        })
        sync()
        assertEquals(1, provider.channelsOf("bookmarks").size)
        assertEquals(listOf("a"), titlesOf(ChannelKind.BOOKMARKS))
    }

    @Test
    fun updateExistingModeNeverCreatesOrDeletesChannels() {
        config.channelDownloads = true
        programs[ChannelKind.DOWNLOADS] = listOf(program("a"))
        sync(ChannelKind.DOWNLOADS, mode = HomeChannels.Mode.UPDATE_EXISTING)
        assertTrue(provider.channelsOf("downloads").isEmpty())

        sync(ChannelKind.DOWNLOADS)
        //stale prefs of the downloads process: the setting looks off, the channel is refreshed anyway
        config.channelDownloads = false
        programs[ChannelKind.DOWNLOADS] = listOf(program("a"), program("b"))
        sync(ChannelKind.DOWNLOADS, mode = HomeChannels.Mode.UPDATE_EXISTING)
        assertEquals(listOf("a", "b"), titlesOf(ChannelKind.DOWNLOADS))
    }

    @Test
    fun theNameFollowsTheLanguage() {
        config.channelTabs = true
        sync()
        val channel = provider.channelsOf("tabs").single()
        channel.put(Channels.COLUMN_DISPLAY_NAME, "Old name")
        sync()
        assertEquals(context.getString(ChannelKind.TABS.title), channel.getAsString(Channels.COLUMN_DISPLAY_NAME))
    }

    @Test
    fun browsableIsReadFromTheChannel() = runBlocking {
        config.channelTabs = true
        sync()
        assertFalse(HomeChannels.isBrowsable(context, ChannelKind.TABS)!!)
        provider.channelsOf("tabs").single().put(Channels.COLUMN_BROWSABLE, 1)
        assertTrue(HomeChannels.isBrowsable(context, ChannelKind.TABS)!!)
        assertNotNull(HomeChannels.isBrowsable(context, ChannelKind.TABS))
    }

    @Test
    fun nothingHappensWhereChannelsAreNotSupported() {
        HomeChannels.supportedForTests = false
        config.channelTabs = true
        assertTrue(sync().isEmpty())
        assertTrue(provider.channels.isEmpty())
    }
}
