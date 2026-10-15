package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.analyzer.Args
import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * #148: `report` aggregates the per-request STT timing (request latency +
 * real-time factor) from the bundle's `sttSentAt`/`sttReceivedAt`, and a
 * legacy (pre-#148) bundle without timing fields still parses and reports —
 * it simply contributes no timed request.
 */
class ReportCommandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runReport(dir: File): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(buffer))
            ReportCommand.run(Args(listOf(dir.path)))
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `report aggregates request latency and real-time factor over timed fragments`() {
        val dir = tmp.newFolder("bundles")

        // timed session: fragment 0 has timing + boundaries, fragment 1 not
        val a = SessionBundle("20261015-1000-abcd1234")
        a.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = "http://10.8.0.16:11437/v1/",
            sttModel = "large-v3",
            llmBaseUrl = null,
            llmModel = null,
            rawMode = false,
            compressAudio = false
        )
        a.fragment(0).apply {
            startMs = 0
            endMs = 21_000
            uploadFile = "frag_000000.wav"
            uploadBytes = 50_000L
            sttSentAt = 1_000L
            sttReceivedAt = 2_000L
        }
        a.fragment(1).apply {
            startMs = 21_000
            endMs = 40_500
            uploadFile = "upload_000001.wav"
            uploadBytes = 60_000L
        }
        File(dir, "a").mkdirs()
        File(dir, "a/session.json").writeText(a.write())

        // legacy (pre-#148) session: parses, contributes no timed request
        val b = SessionBundle("20261010-0900-legacy000")
        b.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = "http://10.8.0.16:11437/v1/",
            sttModel = "large-v3",
            llmBaseUrl = null,
            llmModel = null,
            rawMode = false,
            compressAudio = false
        )
        b.fragment(0).apply {
            startMs = 0
            endMs = 10_000
            uploadFile = "frag_000000.wav"
        }
        File(dir, "b").mkdirs()
        File(dir, "b/session.json").writeText(b.write())

        val out = runReport(dir)

        assertThat(out).contains("2 sessions")
        // the timing columns exist and exactly ONE request was timed
        assertThat(out).contains("reqs")
        assertThat(out).contains("req(s)")
        assertThat(out).contains("RTF")
        assertThat(out).contains("timing (#148, successful attempts): 1 requests")
        // request duration: 2000 - 1000 = 1000 ms -> mean 1.00 s per request
        assertThat(out).contains("1.00")
        // RTF: 1000 ms request / 21_000 ms audio
        assertThat(out).contains("0.05")
        // legacy fragment contributes to the unspecialized columns only
        assertThat(out).contains("3")
        assertThat(out).doesNotContain("NaN")
    }

    @Test
    fun `report without any timed fragments prints no timing block`() {
        val dir = tmp.newFolder("bundles-legacy-only")
        val b = SessionBundle("legacy")
        b.fragment(0).startMs = 0
        b.fragment(0).endMs = 10_000
        File(dir, "b").mkdirs()
        File(dir, "b/session.json").writeText(b.write())

        val out = runReport(dir)

        assertThat(out).doesNotContain("timing (#148")
        assertThat(out).doesNotContain("NaN")
    }
}
