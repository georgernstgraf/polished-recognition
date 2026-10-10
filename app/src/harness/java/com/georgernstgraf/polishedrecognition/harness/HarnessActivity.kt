package com.georgernstgraf.polishedrecognition.harness

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.audio.SeamPolicy
import com.georgernstgraf.polishedrecognition.audio.WavReader
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.gson.GsonBuilder
import java.io.File

/**
 * #122 seam-study harness. Lives ONLY in the `harness` build variant
 * (`src/harness/`) — never part of release/F-Droid. It decodes a 16 kHz mono
 * 16-bit WAV, injects the raw PCM through the production restore seam, drives
 * ONE real transcription through the fragment pipeline, waits for the shadow
 * reference and writes a `harness.json` result. Started by adb with an
 * explicit component + extras, e.g.:
 *
 * ```
 * adb shell am start -n com.georgernstgraf.polishedrecognition/.harness.HarnessActivity \
 *   --es file /sdcard/.../samples/en_monologue_male_300s.wav \
 *   --es mode raw --es format ogg --es policy forced \
 *   --ef fragmentSeconds 10 --ef searchSeconds 2 --ef preRollSeconds 1
 * ```
 *
 * Recognised extras: `file` (required), `label`, `mode` (raw|german|polish),
 * `format` (ogg|wav), `policy` (all|forced|off), `fragmentSeconds`,
 * `searchSeconds`, `preRollSeconds`, `promptChars` (int), `reference` (bool,
 * default true = run the shadow as the seam reference), `timeoutSeconds` (int).
 */
class HarnessActivity : Activity() {

    private val app get() = application as PolishedRecognitionApp
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val main = Handler(Looper.getMainLooper())

    private var finished = false
    private var startedAt = 0L

    private var input = ""
    private var label = ""
    private var mode = "raw"
    private var format = "wav"
    private var policy = "forced"
    private var fragmentSeconds = 10f
    private var searchSeconds = 2f
    private var preRollSeconds = 1f
    private var promptChars = 96
    private var reference = true

    private var completed: VoiceSessionController.Event.Completed? = null
    private var shadowFull: String? = null
    private var shadowError: String? = null
    private var shadowDone = false

    private var savedRawMode = false
    private var savedCompress = false
    private var savedTarget: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        input = intent.getStringExtra("file") ?: return finishRun(false, "missing --es file")
        label = intent.getStringExtra("label") ?: File(input).nameWithoutExtension
        mode = intent.getStringExtra("mode") ?: "raw"
        format = intent.getStringExtra("format") ?: "wav"
        policy = intent.getStringExtra("policy") ?: "forced"
        fragmentSeconds = intent.getFloatExtra("fragmentSeconds", 10f)
        searchSeconds = intent.getFloatExtra("searchSeconds", 2f)
        preRollSeconds = intent.getFloatExtra("preRollSeconds", 1f)
        promptChars = intent.getIntExtra("promptChars", 96)
        reference = intent.getBooleanExtra("reference", true)
        val timeoutMs = intent.getIntExtra("timeoutSeconds", 600) * 1000L

        val settings = app.settingsStore
        savedRawMode = settings.rawMode
        savedCompress = settings.compressAudio
        savedTarget = settings.targetLanguage

        // 1. capture settings for this run (restored in finishRun)
        settings.rawMode = mode.equals("raw", ignoreCase = true)
        settings.compressAudio = format.equals("ogg", ignoreCase = true)
        settings.targetLanguage = if (mode.equals("german", ignoreCase = true)) "German" else null
        // 2. no learned profile: the study pins the size and needs the fragment
        //    join (fullContextAtStop is disabled below) — clear the fit input.
        settings.clearSttProfiles()

        // 3. inject the sample through the production restore seam
        val pcm = try {
            WavPcm.extract(File(input).readBytes())
        } catch (e: Throwable) {
            return finishRun(false, "cannot read WAV '$input': ${e.message}")
        }
        val pcmFile = File(cacheDir, "session.pcm").apply { writeBytes(pcm) }
        File(cacheDir, "session.meta").writeText("{\"durationMs\": ${pcm.size / 32}, \"sessionId\": null}")
        Log.i(TAG, "injected ${pcm.size} B PCM from $input (label=$label mode=$mode format=$format policy=$policy)")

        val controller = VoiceSessionController(
            context = this,
            pipeline = app.transcriptionPipeline,
            settings = settings,
            fragmentBytes = (fragmentSeconds * 32_000).toInt(),
            fragmentSearchBytes = (searchSeconds * 32_000).toInt(),
            fragmentPreRollBytes = (preRollSeconds * 32_000).toInt(),
            seamPolicy = parsePolicy(policy),
            seamEvidence = true,
            onShadowResult = { _, full, err ->
                shadowFull = full.ifBlank { null }
                shadowError = err
                shadowDone = true
                maybeFinish()
            },
            fullContextAtStopEnabled = false,
            logger = app.jsonLogger,
            shadowSttEnabled = { reference },
            fragmentSizeProvider = { fragmentSeconds.toDouble() }
        )

        controller.attach { event ->
            if (event is VoiceSessionController.Event.Completed) {
                completed = event
                maybeFinish()
            }
        }
        if (!controller.restore()) {
            return finishRun(false, "restore() rejected the injected snapshot")
        }
        startedAt = System.currentTimeMillis()
        controller.stopAndTranscribe(null)

        main.postDelayed({
            if (!finished) {
                Log.w(TAG, "harness timeout — finishing with partial results")
                finishRun(true, "timeout")
            }
        }, timeoutMs)
    }

    private fun maybeFinish() {
        // done when the fragment join is delivered AND (if requested) the
        // shadow reference has been written
        if (completed != null && (!reference || shadowDone)) finishRun(true, null)
    }

    private fun finishRun(success: Boolean, error: String?) {
        if (finished) return
        finished = true
        val delivered = completed?.result?.getOrNull()
        val result = linkedMapOf<String, Any?>(
            "success" to success,
            "error" to error,
            "input" to input,
            "label" to label,
            "mode" to mode,
            "format" to format,
            "policy" to policy,
            "fragmentSeconds" to fragmentSeconds,
            "searchSeconds" to searchSeconds,
            "preRollSeconds" to preRollSeconds,
            "promptChars" to promptChars,
            "reference" to reference,
            "deliveredText" to delivered,
            "deliveredWordCount" to delivered?.split(Regex("\\s+"))?.count { it.isNotBlank() },
            "redelivered" to (completed?.redelivered ?: false),
            "partial" to completed?.partial?.let { "${it.failedIndex}/${it.chunkCount}" },
            "sttError" to completed?.result?.exceptionOrNull()?.message,
            "shadowFullText" to shadowFull,
            "shadowError" to shadowError,
            "shadowWordCount" to shadowFull?.split(Regex("\\s+"))?.count { it.isNotBlank() },
            "elapsedMs" to if (startedAt == 0L) null else System.currentTimeMillis() - startedAt
        )
        val dir = File(getExternalFilesDir(null) ?: filesDir, "harness").apply { mkdirs() }
        val json = gson.toJson(result)
        try {
            File(dir, "latest.json").writeText(json)
            File(dir, "${label}__${mode}__${format}__${policy}__${fragmentSeconds}s.json").writeText(json)
        } catch (e: Throwable) {
            Log.e(TAG, "cannot write harness.json", e)
        }
        Log.i(TAG, "harness done success=$success error=$error")
        restoreSettings()
        finish()
    }

    private fun restoreSettings() {
        val settings = app.settingsStore
        settings.rawMode = savedRawMode
        settings.compressAudio = savedCompress
        settings.targetLanguage = savedTarget
    }

    private fun parsePolicy(value: String): SeamPolicy = when (value.lowercase()) {
        "all" -> SeamPolicy.ALL
        "off" -> SeamPolicy.OFF
        else -> SeamPolicy.FORCED_ONLY
    }

    private companion object {
        const val TAG = "SeamHarness"
    }
}
