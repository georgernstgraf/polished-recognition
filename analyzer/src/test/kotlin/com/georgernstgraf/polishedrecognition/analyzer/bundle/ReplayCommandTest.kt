package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.audio.WavWriter
import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/**
 * `replay` (#129) — the exact re-send of what the DEVICE uploaded.
 *
 * The bundle is the whole request: upload bytes, prompt, model, endpoint. The
 * fake STT server here asserts that the replay really sends those (a replay
 * that silently drops the prompt or re-encodes the audio would measure
 * nothing), and that `--ab` adds the two bare arms.
 */
class ReplayCommandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private var responseText = "Hallo Welt"

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.ISO_8859_1)
            synchronized(requests) { requests.add(body) }
            val json = """{"text":"$responseText"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/v1/"

    /**
     * One bundle: fragment 0 bare (`frag_`), fragment 1 STITCHED (composed
     * `upload_` with a pre-roll + prompt) — the shape the device writes.
     */
    private fun bundle(
        sessionId: String = "20261102-1300-replay01",
        stitchedFragment: Boolean = true,
        withBareSibling: Boolean = true,
    ): File {
        val dir = File(tmp.root, "bundles/$sessionId")
        val uploads = File(dir, "uploads").apply { mkdirs() }
        val bundle = SessionBundle(sessionId)
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = url(),
            sttModel = "whisper-large-v3",
            llmBaseUrl = null, llmModel = null,
            rawMode = false, compressAudio = true
        )
        val f0 = bundle.fragment(0)
        f0.startMs = 0
        f0.endMs = 5_000
        f0.uploadFile = "frag_000000.wav"
        f0.sttRaw = "erste Antwort"
        File(uploads, "frag_000000.wav").writeBytes(byteArrayOf(1, 2, 3, 4))

        val f1 = bundle.fragment(1)
        f1.startMs = 5_000
        f1.endMs = 10_000
        f1.stitched = stitchedFragment
        f1.preRollMs = 980
        f1.prompt = "erste Antwort"
        f1.uploadFile = "upload_000001.wav"
        f1.sttRaw = "zweite Antwort"
        File(uploads, "upload_000001.wav").writeBytes(byteArrayOf(9, 8, 7, 6, 5))
        if (stitchedFragment && withBareSibling) {
            File(uploads, "frag_000001.wav").writeBytes(byteArrayOf(7, 7))
        }
        File(dir, "session.json").writeText(bundle.write())
        return File(tmp.root, "bundles")
    }

    private fun replay(vararg extra: String) {
        ReplayCommand.run(
            Args(listOf("--stt-token", "test-token", "--out", File(tmp.root, "out").path) + extra.toList())
        )
    }

    @Test
    fun `replay sends the recorded bytes, model and prompt`() {
        val bundles = bundle()

        replay(bundles.path)

        assertEquals("one request per fragment", 2, requests.size)
        val stitchedRequest = requests[1]
        assertTrue("model must be the recorded one", stitchedRequest.contains("whisper-large-v3"))
        assertTrue("the recorded prompt must be sent", stitchedRequest.contains("erste Antwort"))
        assertTrue(
            "the upload bytes must be replayed verbatim",
            stitchedRequest.contains(String(byteArrayOf(9, 8, 7, 6, 5), Charsets.ISO_8859_1))
        )
        // fragment 0 is bare: no prompt field at all
        assertTrue("a bare fragment carries no prompt", !requests[0].contains("name=\"prompt\""))
    }

    @Test
    fun `the replay is diffed against the recorded text`() {
        bundle()
        responseText = "Hallo Welt"

        replay(tmp.root.path + "/bundles")

        val json = File(tmp.root, "out/replay.json").readText()
        assertTrue("the recorded text is kept for the diff", json.contains("erste Antwort"))
        assertTrue("the replayed text is recorded", json.contains("Hallo Welt"))
    }

    @Test
    fun `ab adds the bare and bare-plus-prompt arms`() {
        val bundles = bundle()

        replay(bundles.path, "--ab")

        // fragment 0: one arm (it is already bare); fragment 1: 3 arms
        assertEquals(4, requests.size)
        val json = File(tmp.root, "out/replay.json").readText()
        assertTrue(json.contains("\"bare\""))
        assertTrue(json.contains("bare+prompt"))
    }

    @Test
    fun `repeat replays the same request several times`() {
        val bundles = bundle()

        replay(bundles.path, "--repeat", "2")

        assertEquals(4, requests.size)
        val json = File(tmp.root, "out/replay.json").readText()
        assertTrue(json.contains("recorded#2"))
    }

    @Test
    fun `a fragment without sttRaw is skipped`() {
        val dir = File(tmp.root, "bundles/20261102-1300-noanswer")
        val uploads = File(dir, "uploads").apply { mkdirs() }
        val bundle = SessionBundle("20261102-1300-noanswer")
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = url(), sttModel = "whisper-large-v3",
            llmBaseUrl = null, llmModel = null, rawMode = false, compressAudio = false
        )
        val f = bundle.fragment(0)
        f.startMs = 0
        f.endMs = 5_000
        f.uploadFile = "frag_000000.wav"
        f.sttError = "HTTP 500"
        File(uploads, "frag_000000.wav").writeBytes(byteArrayOf(1))
        File(dir, "session.json").writeText(bundle.write())

        replay(File(tmp.root, "bundles").path)

        assertEquals("nothing to replay without a response", 0, requests.size)
    }

    @Test
    fun `the bare arm is derived from the composed upload when no sibling exists`() {
        // The real corpus shape: uploads/ holds ONLY the composed file. The bare
        // arm must be materialized: drop the 980-ms pre-roll head, hold the
        // 5000-ms boundary span. Marker bytes make the split visible: 0x11 is
        // pre-roll only, 0x22 is fragment audio.
        val dir = File(tmp.root, "bundles/20261102-1300-derived1")
        val uploads = File(dir, "uploads").apply { mkdirs() }
        val bundle = SessionBundle("20261102-1300-derived1")
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = url(), sttModel = "whisper-large-v3",
            llmBaseUrl = null, llmModel = null, rawMode = false, compressAudio = false
        )
        val f0 = bundle.fragment(0)
        f0.startMs = 0
        f0.endMs = 5_000
        f0.uploadFile = "frag_000000.wav"
        f0.sttRaw = "erste Antwort"
        File(uploads, "frag_000000.wav").writeBytes(WavWriter.write(ByteArray(5_000 * 32) { 0x11 }, 16_000))

        val composed = ByteArray(980 * 32) { 0x11 } + ByteArray(5_000 * 32) { 0x22 }
        val f1 = bundle.fragment(1)
        f1.startMs = 5_000
        f1.endMs = 10_000
        f1.stitched = true
        f1.preRollMs = 980
        f1.prompt = "erste Antwort"
        f1.uploadFile = "upload_000001.wav"
        f1.sttRaw = "zweite Antwort"
        File(uploads, "upload_000001.wav").writeBytes(WavWriter.write(composed, 16_000))
        File(dir, "session.json").writeText(bundle.write())

        replay(File(tmp.root, "bundles").path, "--ab")

        assertEquals("fragment 0 (1 arm) + fragment 1 (3 arms)", 4, requests.size)
        val bareArm = requests.last()
        // ISO-8859-1 decoding: 0x22 -> '"', 0x11 -> U+0011
        assertTrue("the bare arm carries the fragment audio", bareArm.contains("\"".repeat(100)))
        assertFalse("the pre-roll head must be gone", bareArm.contains("\u0011".repeat(100)))
    }

    @Test
    fun `bareName maps the composed upload to its bare sibling`() {
        assertEquals("frag_000004.ogg", ReplayCommand.bareName("upload_000004.ogg"))
        assertEquals("frag_000004.wav", ReplayCommand.bareName("frag_000004.wav"))
    }
}
