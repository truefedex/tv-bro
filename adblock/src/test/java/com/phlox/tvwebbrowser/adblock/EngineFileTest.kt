package com.phlox.tvwebbrowser.adblock

import com.phlox.tvwebbrowser.adblock.TestPsl.psl
import com.phlox.tvwebbrowser.adblock.engine.EngineFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class EngineFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val lines = arrayOf(
        "||ads.example.net^", "@@||ads.example.net/ok^", "||track.example.org^\$third-party,image",
        "/banner\\d+\\.gif/", "/ads.js\$domain=example.com|~sub.example.com", "@@||news.example.com^\$document",
        "-advert-", "ad_banner", "||important.example^\$important",
    )
    private val requests = listOf(
        "https://ads.example.net/1.js" to "https://example.com/",
        "https://ads.example.net/ok/1.js" to "https://example.com/",
        "https://track.example.org/p.gif" to "https://example.com/",
        "https://x.com/banner12.gif" to "https://example.com/",
        "https://x.com/ads.js" to "https://sub.example.com/",
        "https://x.com/ads.js" to "https://www.example.com/",
        "https://ads.example.net/1.js" to "https://news.example.com/",
        "https://x.com/top-advert-1.gif" to "https://example.com/",
        "https://x.com/ad_banner.png" to "https://example.com/",
        "https://important.example/a" to "https://example.com/",
    )
    private val types = listOf(ResourceType.SCRIPT, ResourceType.IMAGE, ResourceType.SCRIPT or ResourceType.XHR)

    private fun results(engine: FilterEngine) = requests.flatMap { (url, page) ->
        types.map { engine.check(url, page, it) }
    }

    @Test
    fun loadedEngineDecidesAsTheCompiledOne() {
        val compiled = TestPsl.compile(*lines)
        val file = File(temp.root, "engine.bin")
        compiled.writeTo(file)
        val loaded = FilterEngine.load(file, psl)
        assertEquals(results(compiled), results(loaded))
        assertTrue(results(loaded).contains(MatchResult.BLOCK) && results(loaded).contains(MatchResult.ALLOW))
        assertEquals(compiled.filterCount, loaded.filterCount)
        assertEquals(compiled.createdAt, loaded.createdAt)
        assertFalse(File(temp.root, "engine.bin.tmp").exists())
    }

    @Test
    fun fingerprintIsKept() {
        val compiler = FilterCompiler()
        compiler.addList(lines.asSequence())
        val file = File(temp.root, "engine.bin")
        compiler.build(psl, fingerprint = 0x1234_5678_9abc_def0).writeTo(file)
        assertEquals(0x1234_5678_9abc_def0, FilterEngine.load(file, psl).fingerprint)
    }

    @Test
    fun writingAgainReplacesTheFile() {
        val file = File(temp.root, "engine.bin")
        TestPsl.compile("||a.example.net^").writeTo(file)
        TestPsl.compile("||b.example.net^").writeTo(file)
        val loaded = FilterEngine.load(file, psl)
        assertEquals(MatchResult.NONE, loaded.check("https://a.example.net/"))
        assertEquals(MatchResult.BLOCK, loaded.check("https://b.example.net/"))
    }

    @Test
    fun damagedFilesAreRejected() {
        val file = File(temp.root, "engine.bin")
        TestPsl.compile(*lines).writeTo(file)
        val good = file.readBytes()

        assertRejected(ByteArray(0))
        assertRejected(good.copyOf(20))
        assertRejected(good.copyOf(good.size - 8))
        assertRejected(good + ByteArray(8))
        //a byte of the body
        assertRejected(good.copyOf().also { it[good.size / 2] = (it[good.size / 2] + 1).toByte() })
        //another magic
        assertRejected(good.copyOf().also { it[0] = 'X'.code.toByte() })
        //another format version
        assertRejected(good.copyOf().also { it[4] = (it[4] + 1).toByte() })
        assertRejected(ByteArray(4096) { it.toByte() })
    }

    @Test
    fun badContentWithAGoodChecksumBlocksNothingAndDoesNotCrash() {
        val file = File(temp.root, "engine.bin")
        TestPsl.compile(*lines).writeTo(file)
        //as a buggy version could write it: offsets out of the pool, then a correct CRC
        val content = EngineFile.read(file.readBytes())
        val sections = HashMap(content.sections)
        sections[EngineFile.PATTERN_OFFSETS] = IntArray((sections[EngineFile.FLAGS] as IntArray).size) { 1_000_000_000 }
        EngineFile.write(file, content.fingerprint, content.createdAt, sections)
        val engine = FilterEngine.load(file, psl)
        assertEquals(MatchResult.NONE, engine.check("https://x.com/top-advert-1.gif"))
        assertTrue(engine.errors > 0)
    }

    private fun assertRejected(bytes: ByteArray) {
        try {
            FilterEngine.read(bytes, psl)
            fail("loaded a bad file of ${bytes.size} bytes")
        } catch (e: AdblockFormatException) {
            //expected
        }
    }

    @Test
    fun checksFromManyThreadsWhileTheEngineIsReplaced() {
        val engines = listOf(TestPsl.compile(*lines), TestPsl.compile(*lines, "||x.com^"))
        val expected = engines.map { results(it) }
        val current = AtomicReference(engines[0])
        val start = CountDownLatch(1)
        val failures = AtomicReference<Throwable?>(null)
        val running = AtomicBoolean(true)
        val threads = (1..8).map {
            thread {
                start.await()
                try {
                    while (running.get()) {
                        val engine = current.get()
                        val got = results(engine)
                        if (got != expected[engines.indexOf(engine)]) throw AssertionError("results of another engine")
                    }
                } catch (e: Throwable) {
                    failures.compareAndSet(null, e)
                }
            }
        }
        start.countDown()
        repeat(200) {
            current.set(engines[it % 2])
            Thread.sleep(1)
        }
        running.set(false)
        threads.forEach { it.join() }
        failures.get()?.let { throw it }
    }
}
