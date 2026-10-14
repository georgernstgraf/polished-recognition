package com.georgernstgraf.polishedrecognition.harness

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.georgernstgraf.polishedrecognition.PolishedRecognitionApp
import com.georgernstgraf.polishedrecognition.audio.FragmentPreparer
import com.georgernstgraf.polishedrecognition.audio.SearchSpan
import com.georgernstgraf.polishedrecognition.audio.SeamPolicy
import com.georgernstgraf.polishedrecognition.pipeline.VoiceSessionController
import com.google.gson.GsonBuilder
import java.io.File

/**
 * #122 seam-study harness. Lives ONLY in the `harness` build variant
 * (`src/harness/`) — never part of release/F-Droid. It decodes a 16 kHz mono
 * 16-bit WAV, injects the raw PCM through the production restore seam, drives
 * ONE real transcription through the fragment pipeline and writes a
 * `harness.json` result. Started by adb with an
 * explicit component + extras, e.g.:
 *
 * ```
 * adb shell am start -n com.georgernstgraf.polishedrecognition/.harness.HarnessActivity \
 *   --es file /sdcard/.../samples/en_monologue_male_300s.wav \
 *   --es mode raw --es format wav --es policy qgate \
 *   --ef fragmentSeconds 10 --ef searchSeconds 2 --ef preRollSeconds 1 \
 *   --ef axisK 0.65 --ef axisC 100.0 --ef qualityMin 10.0 \
 *   --ei marginKMs 150 --es searchSpan lateHalf
 * ```
 *
 * Recognised extras: `file` (required), `label`, `mode` (raw|german|polish),
 * `format` (ogg|wav), `policy` (all|forced|off|qgate, DEFAULT qgate = the
 * #128 production arm), `fragmentSeconds`, `searchSeconds`,
 * `preRollSeconds`, `promptChars` (int), `timeoutSeconds` (int), and the
 * #126/#128 silence-quality knobs (#129 research pass): `axisK` (float,
 * default 0.65), `axisC` (float, default 100.0), `qualityMin` (float,
 * default 10.0), `marginKMs` (int, default 150), `searchSpan`
 * (lateHalf default | fullWindow). Defaults mirror the PROVISIONAL
 * production values ([FragmentPreparer] constants). The #124
 * `energyFallback` extra is GONE — the parameter is inert since #126.
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
    private var policy = "qgate"
    private var fragmentSeconds = 10f
    private var searchSeconds = 2f
    private var preRollSeconds = 1f
    private var promptChars = 96
    private var axisK = FragmentPreparer.DEFAULT_AXIS_K
    private var axisC = FragmentPreparer.DEFAULT_AXIS_C
    private var qualityMin = FragmentPreparer.DEFAULT_QUALITY_MIN
    private var marginKMs = FragmentPreparer.DEFAULT_MARGIN_K_MS
    private var searchSpan = "lateHalf"

    private var completed: VoiceSessionController.Event.Completed? = null

    private var savedRawMode = false
    private var savedCompress = false
    private var savedTarget: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        input = intent.getStringExtra("file") ?: return finishRun(false, "missing --es file")
        label = intent.getStringExtra("label") ?: File(input).nameWithoutExtension
        mode = intent.getStringExtra("mode") ?: "raw"
        format = intent.getStringExtra("format") ?: "wav"
        policy = intent.getStringExtra("policy") ?: "qgate"
        fragmentSeconds = intent.getFloatExtra("fragmentSeconds", 10f)
        searchSeconds = intent.getFloatExtra("searchSeconds", 2f)
        preRollSeconds = intent.getFloatExtra("preRollSeconds", 1f)
        promptChars = intent.getIntExtra("promptChars", 96)
        // #129 research-pass knobs — defaults = the PROVISIONAL production
        // values (#126/#128); am start's --ef/--ei carry the sweep arms.
        axisK = intent.getFloatExtra("axisK", FragmentPreparer.DEFAULT_AXIS_K.toFloat()).toDouble()
        axisC = intent.getFloatExtra("axisC", FragmentPreparer.DEFAULT_AXIS_C.toFloat()).toDouble()
        qualityMin = intent.getFloatExtra("qualityMin", FragmentPreparer.DEFAULT_QUALITY_MIN.toFloat()).toDouble()
        marginKMs = intent.getIntExtra("marginKMs", FragmentPreparer.DEFAULT_MARGIN_K_MS)
        searchSpan = intent.getStringExtra("searchSpan") ?: "lateHalf"
        val timeoutMs = intent.getIntExtra("timeoutSeconds", 600) * 1000L

        val settings = app.settingsStore
        savedRawMode = settings.rawMode
        savedCompress = settings.compressAudio
        savedTarget = settings.targetLanguage

        // 1. capture settings for this run (restored in finishRun)
        settings.rawMode = mode.equals("raw", ignoreCase = true)
        settings.compressAudio = format.equals("ogg", ignoreCase = true)
        settings.targetLanguage = if (mode.equals("german", ignoreCase = true)) "German" else null
        // 2. no learned profile: the study pins the exact upload sizes and
        //    needs clean (S, t) samples — clear the fit input.
        settings.clearSttProfiles()

        // 3. inject the sample through the production restore seam
        val pcm = try {
            WavPcm.extract(File(input).readBytes())
        } catch (e: Throwable) {
            return finishRun(false, "cannot read WAV '$input': ${e.message}")
        }
        val pcmFile = File(cacheDir, "session.pcm").apply { writeBytes(pcm) }
        File(cacheDir, "session.meta").writeText("{\"durationMs\": ${pcm.size / 32}, \"sessionId\": null}")
        Log.i(TAG, "injected ${pcm.size} B PCM from $input (label=$label mode=$mode format=$format policy=$policy axisK=$axisK axisC=$axisC qualityMin=$qualityMin marginKMs=$marginKMs searchSpan=$searchSpan)")

        val controller = VoiceSessionController(
            context = this,
            pipeline = app.transcriptionPipeline,
            settings = settings,
            // No fragmentSizeProvider on purpose (#124): an explicit
            // fragmentSearchBytes keeps its exact semantics only without a
            // provider (with one wired, the controller derives search =
            // max(search, fragment)); the harness pins the size via
            // fragmentBytes directly.
            fragmentBytes = (fragmentSeconds * 32_000).toInt(),
            fragmentSearchBytes = (searchSeconds * 32_000).toInt(),
            fragmentPreRollBytes = (preRollSeconds * 32_000).toInt(),
            seamPolicy = parsePolicy(policy),
            promptMaxChars = promptChars,
            // #129 research-pass knobs: plumb the am start extras into the
            // controller (defaults = the PROVISIONAL production values).
            fragmentAxisK = axisK,
            fragmentAxisC = axisC,
            fragmentQualityMin = qualityMin,
            fragmentMarginKMs = marginKMs,
            fragmentSearchSpan = parseSearchSpan(searchSpan),
            seamEvidence = true,
            logger = app.jsonLogger,
            // #127: retain the harness run's audio evidence too — the
            // harness BuildConfig defaults the toggle ON, and study runs
            // are exactly the material post-mortems want.
            audioEvidenceStore = app.audioEvidenceStore,
            audioEvidenceEnabled = { settings.audioEvidence }
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
        // done when the fragment join is delivered (the seam reference comes
        // from the PC-side offline Whisper comparison, #144)
        if (completed != null) finishRun(true, null)
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
            // #129: every knob is recorded so sweep arms are attributable
            // in analysis — the results file is the single source for what
            // an arm actually ran with.
            "axisK" to axisK,
            "axisC" to axisC,
            "qualityMin" to qualityMin,
            "marginKMs" to marginKMs,
            "searchSpan" to searchSpan,
            "deliveredText" to delivered,
            "deliveredWordCount" to delivered?.split(Regex("\\s+"))?.count { it.isNotBlank() },
            "redelivered" to (completed?.redelivered ?: false),
            "partial" to completed?.partial?.let { "${it.failedIndex}/${it.chunkCount}" },
            "sttError" to completed?.result?.exceptionOrNull()?.message,
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
        // #129: qgate (the #128 production default) is the DEFAULT arm —
        // the research pass's mainline; forced/all/off remain selectable
        // as frozen A/B arms.
        "qgate" -> SeamPolicy.Q_GATE
        else -> SeamPolicy.FORCED_ONLY
    }

    private fun parseSearchSpan(value: String): SearchSpan = when (value.lowercase()) {
        // #126: lateHalf is the production default (owner decision);
        // fullWindow is the #129 harness A/B arm.
        "fullwindow" -> SearchSpan.FULL_WINDOW
        else -> SearchSpan.LATE_HALF
    }

    private companion object {
        const val TAG = "SeamHarness"
    }
}
