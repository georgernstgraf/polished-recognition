package com.georgernstgraf.polishedrecognition.pipeline

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

/**
 * #142: the forensic session manifest — merge semantics for the fragment
 * records (the preparer writes cut metadata, the transcriber writes upload
 * + STT outcomes, the rebuild path re-commits), the pipeline-outcome
 * consumption (LLM block + raw-text stash, transient) and the JSON shape
 * (the `session.json` the PC analyzer reads).
 */
class SessionBundleTest {

    @Test
    fun `fragment records merge across writers instead of replacing`() {
        val bundle = SessionBundle("testsess")
        // the preparer: boundaries + START-seam decision
        bundle.fragment(0).let { f ->
            f.startMs = 0L
            f.endMs = 10_000L
            f.stitched = false
            f.seamForced = true
        }
        // the transcriber: upload + STT outcome (SAME record object)
        bundle.fragment(0).let { f ->
            f.uploadFile = "upload_000000.ogg"
            f.sttRaw = "hello hello"
            f.sttTrimmed = "hello"
            f.echoTokens = 1
            f.prompt = null
        }

        val f = bundle.fragments[0]!!
        assertThat(f.startMs).isEqualTo(0L)
        assertThat(f.endMs).isEqualTo(10_000L)
        assertThat(f.stitched).isFalse()
        assertThat(f.seamForced).isTrue()
        assertThat(f.uploadFile).isEqualTo("upload_000000.ogg")
        assertThat(f.sttRaw).isEqualTo("hello hello")
        assertThat(f.sttTrimmed).isEqualTo("hello")
        assertThat(f.echoTokens).isEqualTo(1)
    }

    @Test
    fun `recordOutcome fills the llm block and stashes the raw text transiently`() {
        val bundle = SessionBundle("testsess")
        bundle.recordOutcome(
            PipelineOutcome(
                rawText = "raw transcript",
                rawMode = false,
                systemPrompt = "system",
                userPrompt = "user",
                llmResponse = "polished"
            )
        )

        assertThat(bundle.llm).isEqualTo(
            SessionBundle.LlmPass(
                rawMode = false,
                systemPrompt = "system",
                userPrompt = "user",
                response = "polished",
                error = null
            )
        )
        assertThat(bundle.pipelineOutcome?.rawText).isEqualTo("raw transcript")
    }

    @Test
    fun `llm error reaches the llm block`() {
        val bundle = SessionBundle("testsess")
        bundle.recordOutcome(
            PipelineOutcome(
                rawText = "raw",
                rawMode = false,
                llmError = "LLM post-processing failed: HTTP 500"
            )
        )
        assertThat(bundle.llm?.error).isEqualTo("LLM post-processing failed: HTTP 500")
        assertThat(bundle.llm?.response).isNull()
    }

    @Test
    fun `the transient outcome never leaks into the JSON`() {
        val bundle = SessionBundle("testsess")
        bundle.recordOutcome(
            PipelineOutcome(rawText = "raw transcript", rawMode = true)
        )
        bundle.fragment(0).let { f ->
            f.startMs = 0L
            f.endMs = 10_000L
            f.sttRaw = "raw transcript"
        }

        val json = bundle.toJson()
        val parsed = Gson().fromJson(json, Map::class.java) as Map<String, Any>
        // the raw text appears ONLY in the fragment record — not as a
        // top-level "pipelineOutcome" blob
        assertThat(parsed.containsKey("pipelineOutcome")).isFalse()
        val fragments = parsed["fragments"] as List<Map<String, Any>>
        assertThat(fragments).hasSize(1)
        assertThat(fragments[0]["sttRaw"]).isEqualTo("raw transcript")
    }

    @Test
    fun `pretty JSON carries the full forensic shape`() {
        val bundle = SessionBundle("20261014-2115-abcd1234").apply {
            startedAt = 1_000L
            endedAt = 2_000L
            provider = SessionBundle.ProviderInfo(
                sttBaseUrl = "http://gregor/v1/",
                sttModel = "large-v3",
                llmBaseUrl = null,
                llmModel = null,
                rawMode = true,
                compressAudio = false
            )
            result = SessionBundle.ResultRecord(
                rawFullText = "raw",
                finalText = "raw",
                deliveredAt = 2_000L,
                deliveryKind = "inserted"
            )
            fragment(0).let { f ->
                f.uploadFile = "upload_000000.wav"
                f.preRollMs = null
                f.sttRaw = "raw"
                f.sttTrimmed = "raw"
                f.echoTokens = 0
            }
        }

        val parsed = Gson().fromJson(bundle.write(), Map::class.java) as Map<String, Any>
        assertThat(parsed["sessionId"]).isEqualTo("20261014-2115-abcd1234")
        assertThat(parsed["startedAt"]).isEqualTo(1_000.0)
        val result = parsed["result"] as Map<*, *>
        assertThat(result["deliveryKind"]).isEqualTo("inserted")
        val provider = parsed["provider"] as Map<*, *>
        assertThat(provider["sttModel"]).isEqualTo("large-v3")
        val fragments = parsed["fragments"] as List<Map<String, Any>>
        assertThat(fragments[0]["uploadFile"]).isEqualTo("upload_000000.wav")
        // nulls are omitted (Gson default) — the analyzer sees a lean shape
        assertThat(fragments[0].containsKey("preRollMs")).isFalse()
    }
}
