package com.georgernstgraf.polishedrecognition.analyzer.analysis

import com.georgernstgraf.polishedrecognition.analyzer.analysis.Analysis.FragmentEvidence
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test
import java.io.File

/**
 * The #129 fixture regression — the KNOWN expected values of the synthetic
 * fixture `testdata/quality-fixture/` (now mirrored into this module's test
 * resources) asserted against the ported analysis core. Every value here is
 * also asserted by `analyze.py --selftest`; the port must agree with the
 * Python original value-for-value.
 */
class FixtureRegressionTest {

    private val gson = Gson()

    private val fixtureDirName = "s1_fixture60__k0.65_c100.0_Q10.0_m150_lateHalf"

    private fun resource(name: String): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("quality-fixture/$fixtureDirName/$name")
            ?: throw IllegalStateException("fixture resource missing: $name")
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonList(name: String): List<Map<String, Any?>> =
        gson.fromJson(resource(name), List::class.java) as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(name: String): Map<String, Any?> =
        gson.fromJson(resource(name), Map::class.java) as Map<String, Any?>

    /**
     * Maps the legacy fixture files onto the canonical inputs: the
     * stt-fragment.json records carry the stored (trimmed) transcripts,
     * seamForced, echoTokens and the on-device seamOverlapTokens; the
     * prepare_all.json records carry the END-cut quality block of fragment
     * i-1 = the START-seam block of fragment i; harness.json carries the
     * join (delivered) and the reference (shadow) text.
     */
    private fun analyzeFixture(): Analysis.Analysis {
        val harness = jsonMap("harness.json")
        val frags = jsonList("stt-fragment.json")
        val prepare = jsonList("prepare_all.json")
        val qByFragment = prepare
            .filter { it.containsKey("Q") }
            .associate { (it["fragment"] as Double).toInt() to (it["Q"] as Double) }
        val stitchedByFragment = prepare
            .filter { it.containsKey("stitched") }
            .associate { (it["fragment"] as Double).toInt() to (it["stitched"] as Boolean) }
        val evidence = frags.map { f ->
            val idx = (f["fragment"] as Double).toInt()
            FragmentEvidence(
                index = idx,
                text = f["text"] as String?,
                seamForced = f["seamForced"] as Boolean?,
                // The fixture's fragment record carries the START-seam
                // decision of THIS fragment (uploadWithPreRoll mirrors it);
                // the prepare record of fragment i-1 is the same cut.
                stitched = stitchedByFragment[idx - 1],
                q = qByFragment[idx - 1],
                echoTokens = (f["echoTokens"] as Double).toInt(),
                deviceOverlapTokens = (f["seamOverlapTokens"] as Double).toInt()
            )
        }
        return Analysis.analyze(
            evidence,
            joinText = harness["deliveredText"] as String,
            referenceText = harness["shadowFullText"] as String
        )
    }

    @Test
    fun `the 129 fixture analysis matches every known expected value`() {
        val r = analyzeFixture()

        assertThat(r.fragments).isEqualTo(3)
        assertThat(r.seams).isEqualTo(2)
        assertThat(r.join).isEqualTo(18)
        assertThat(r.reference).isEqualTo(20)
        assertThat(r.ratio).isEqualTo(0.9)
        assertThat(r.reliable).isTrue()
        assertThat(r.extra).isEqualTo(0)
        assertThat(r.missing).isEqualTo(2)
        assertThat(r.forcedSeams).isEqualTo(0)
        assertThat(r.silenceSeams).isEqualTo(2)
        assertThat(r.joinLoops).isEqualTo(0)
        assertThat(r.referenceLoops).isEqualTo(0)
        assertThat(r.seamsUnclean).isEqualTo(1)
        assertThat(r.seamOverlapTotal).isEqualTo(2)
        assertThat(r.wordSplits).isEqualTo(1)
        assertThat(r.qMedian).isEqualTo(21.0)
        assertThat(r.qP25).isEqualTo(2.0)
        assertThat(r.qP75).isEqualTo(40.0)
        assertThat(r.qStitchedMedian).isEqualTo(2.0)
        assertThat(r.qBareMedian).isEqualTo(40.0)
        assertThat(r.qUncleanMedian).isEqualTo(2.0)
        assertThat(r.qCleanMedian).isEqualTo(40.0)
        assertThat(r.qSpearman).isEqualTo(-1.0)
        assertThat(r.trimFalsePositives).isEqualTo(1)
    }

    @Test
    fun `per-seam records match the fixture scenario`() {
        val r = analyzeFixture()

        assertThat(r.perSeam).hasSize(2)

        // seam 1 (fragment 0 -> 1): the UNCLEAN case — Q = 2.0 stitches
        // under qualityMin 10.0, "fieber und" overlaps, word-split flag set.
        val s1 = r.perSeam[0]
        assertThat(s1.seam).isEqualTo(1)
        assertThat(s1.q).isEqualTo(2.0)
        assertThat(s1.stitched).isTrue()
        assertThat(s1.overlap).isEqualTo(2)
        assertThat(s1.split).isTrue()
        assertThat(s1.unclean).isTrue()
        assertThat(s1.tokMirrorMismatch).isFalse()

        // seam 2 (fragment 1 -> 2): the CLEAN case — Q = 40.0 stays bare.
        val s2 = r.perSeam[1]
        assertThat(s2.seam).isEqualTo(2)
        assertThat(s2.q).isEqualTo(40.0)
        assertThat(s2.stitched).isFalse()
        assertThat(s2.overlap).isEqualTo(0)
        assertThat(s2.split).isFalse()
        assertThat(s2.unclean).isFalse()
        assertThat(s2.tokMirrorMismatch).isFalse()
    }

    @Test
    fun `per-fragment rows carry words, echo and the missing attribution`() {
        val r = analyzeFixture()
        assertThat(r.perFragment).hasSize(3)

        val f2 = r.perFragment[2]
        assertThat(f2.words).isEqualTo(5)
        assertThat(f2.echo).isEqualTo(2)
        assertThat(f2.missing).isEqualTo(2) // the trim false positive's evidence
        val f0 = r.perFragment[0]
        assertThat(f0.words).isEqualTo(6)
        assertThat(f0.extra).isEqualTo(0)
        assertThat(f0.missing).isEqualTo(0)
    }

    @Test
    fun `err100w is 10 dot 0 for the fixture`() {
        val r = analyzeFixture()
        val err = Stats.round2((r.extra + r.missing) / maxOf(1, r.reference).toDouble() * 100)
        assertThat(err).isEqualTo(10.0)
    }

    @Test
    fun `the fixture files are in sync with the repo original`() {
        // The test-resource mirror must stay identical to the tools copy so
        // the regression tracks the fixture the research docs describe.
        val repoFixture = File(
            "tools/seam-study/testdata/quality-fixture", fixtureDirName
        )
        if (!repoFixture.isDirectory) return // running outside the repo checkout
        for (name in listOf("harness.json", "stt-fragment.json", "prepare_all.json", "arm.json")) {
            val expected = File(repoFixture, name).readText().trim()
            val actual = resource(name).trim()
            assertThat(actual).isEqualTo(expected)
        }
    }
}
