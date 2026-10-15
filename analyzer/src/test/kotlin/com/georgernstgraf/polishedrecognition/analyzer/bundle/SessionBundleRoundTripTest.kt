package com.georgernstgraf.polishedrecognition.analyzer.bundle

import com.georgernstgraf.polishedrecognition.pipeline.SessionBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The zero-drift guard (#146): the analyzer parses `session.json` with its
 * OWN DTOs ([BundleDto] and friends) while the WRITE side is the REAL
 * `:core` [SessionBundle] the device runs. This test round-trips a fully
 * populated bundle through `SessionBundle.write()` → `BundleIo.parse()` and
 * asserts every field survives — a field added to SessionBundle without a
 * mirror here fails this suite, so the analyzer can never silently miss
 * forensic evidence.
 */
class SessionBundleRoundTripTest {

    @Test
    fun `a fully populated bundle round-trips through the analyzer DTOs`() {
        val bundle = SessionBundle(sessionId = "20261015-1015-abcd1234")
        bundle.startedAt = 1_760_510_100_000L
        bundle.endedAt = 1_760_510_160_000L
        bundle.provider = SessionBundle.ProviderInfo(
            sttBaseUrl = "http://10.8.0.16:11437/v1/",
            sttModel = "large-v3",
            llmBaseUrl = "http://llm.example/v1/",
            llmModel = "qwen3.8-27b",
            rawMode = false,
            compressAudio = true
        )
        bundle.llm = SessionBundle.LlmPass(
            rawMode = false,
            systemPrompt = "sys",
            userPrompt = "usr",
            response = "polished text",
            error = null
        )
        bundle.result = SessionBundle.ResultRecord(
            rawFullText = "raw full text",
            finalText = "final text",
            deliveredAt = 1_760_510_160_000L,
            deliveryKind = "inserted",
            error = null
        )
        val f0 = bundle.fragment(0)
        f0.startMs = 0
        f0.endMs = 21_000
        f0.stitched = false
        f0.seamForced = false
        f0.sttRaw = "raw"
        f0.sttTrimmed = "trimmed"
        f0.echoTokens = 0
        f0.prompt = null
        val f1 = bundle.fragment(1)
        f1.startMs = 21_000
        f1.endMs = 40_500
        f1.uploadFile = "upload_000001.wav"
        f1.preRollMs = 1000
        f1.stitched = true
        f1.seamForced = false
        f1.quality = linkedMapOf(
            "M" to 5312.4,
            "min" to 120.5,
            "max" to 18110.2,
            "X" to 3553.1,
            "Q" to 2.0,
            "centroidMs" to 512.5
        )
        f1.sttRaw = "echo raw text"
        f1.sttTrimmed = "trimmed text"
        f1.echoTokens = 2
        f1.prompt = "the conditioning prompt"
        val f2 = bundle.fragment(2)
        f2.seamForced = true
        f2.sttError = "HTTP 401"

        val json = bundle.write()
        val parsed = BundleIo.parse(json)

        assertThat(parsed.sessionId).isEqualTo(bundle.sessionId)
        assertThat(parsed.startedAt).isEqualTo(bundle.startedAt)
        assertThat(parsed.endedAt).isEqualTo(bundle.endedAt)
        assertThat(parsed.provider?.sttBaseUrl).isEqualTo("http://10.8.0.16:11437/v1/")
        assertThat(parsed.provider?.sttModel).isEqualTo("large-v3")
        assertThat(parsed.provider?.llmModel).isEqualTo("qwen3.8-27b")
        assertThat(parsed.provider?.rawMode).isFalse()
        assertThat(parsed.provider?.compressAudio).isTrue()
        assertThat(parsed.llm?.response).isEqualTo("polished text")
        assertThat(parsed.result?.rawFullText).isEqualTo("raw full text")
        assertThat(parsed.result?.finalText).isEqualTo("final text")
        assertThat(parsed.result?.deliveryKind).isEqualTo("inserted")

        assertThat(parsed.fragments).hasSize(3)
        val p0 = parsed.fragments[0]
        assertThat(p0.index).isEqualTo(0)
        assertThat(p0.startMs).isEqualTo(0)
        assertThat(p0.endMs).isEqualTo(21_000)
        assertThat(p0.stitched).isFalse()
        assertThat(p0.seamForced).isFalse()
        assertThat(p0.sttTrimmed).isEqualTo("trimmed")
        assertThat(p0.echoTokens).isEqualTo(0)
        val p1 = parsed.fragments[1]
        assertThat(p1.uploadFile).isEqualTo("upload_000001.wav")
        assertThat(p1.preRollMs).isEqualTo(1000)
        assertThat(p1.stitched).isTrue()
        assertThat(p1.quality?.get("Q")).isEqualTo(2.0)
        assertThat(p1.quality?.get("M")).isEqualTo(5312.4)
        assertThat(p1.quality?.get("centroidMs")).isEqualTo(512.5)
        assertThat(p1.echoTokens).isEqualTo(2)
        assertThat(p1.prompt).isEqualTo("the conditioning prompt")
        val p2 = parsed.fragments[2]
        assertThat(p2.seamForced).isTrue()
        assertThat(p2.sttError).isEqualTo("HTTP 401")
    }

    @Test
    fun `quality Q is extractable via the helper`() {
        val bundle = SessionBundle(sessionId = "s")
        bundle.fragment(3).quality = mapOf("Q" to 12.5)
        val parsed = BundleIo.parse(bundle.write())
        val frag = parsed.fragments.first { it.index == 3 }
        assertThat(BundleIo.q(frag)).isEqualTo(12.5)
        assertThat(BundleIo.durationMs(frag)).isNull() // no boundaries recorded
    }
}
