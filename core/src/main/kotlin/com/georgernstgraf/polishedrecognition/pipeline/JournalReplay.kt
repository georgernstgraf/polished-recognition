package com.georgernstgraf.polishedrecognition.pipeline

import com.georgernstgraf.polishedrecognition.config.SttProviderConfig
import java.io.File

/**
 * #185: the recovery re-run — feeds a journal session's MISSING fragments back
 * through the STT provider and returns the joined transcript.
 *
 * ## Why per fragment, not one reconstructed WAV
 *
 * The journal stores the **composed upload** (pre-roll + fragment) per
 * fragment, byte-identical to what the provider already saw (#182). Replaying
 * the fragments one by one keeps every property the live path has:
 *
 * - the recorded prompt conditions the decoder exactly like the first attempt,
 * - the acoustic pre-roll echo is trimmed against the previous transcript
 *   ([EchoTrim] — the same function the live worker uses),
 * - a forced seam gets the same [LoopGuard] collapse,
 * - `session_id`/`fragment_index` stay the durable identity (#184), so a
 *   server that dedupes answers from its cache instead of appending twice.
 *
 * Reconstructing one WAV from the pieces would only work for WAV (`compress
 * audio` uploads OGG, which needs a decoder the app does not have) and would
 * throw away the prompts. There is nothing to gain.
 *
 * ## Only the gaps
 *
 * A session is unfinished because SOME fragments failed; the others are `DONE`
 * and their trimmed text is on disk. Those are never re-sent — their text is
 * spliced in index order. Only a fragment without a stored transcript costs a
 * request.
 *
 * ## Failure policy
 *
 * The re-run stops at the first failure and keeps the journal as it is (a
 * `markFailed` records the class + HTTP code, so the UI can explain it): a
 * recovery attempt is one deliberate try, not a second retry storm. The
 * runner's own transient retries (with `Retry-After`) still apply inside a
 * single fragment. An `AUTH` failure therefore ends the run immediately, and
 * every completed fragment keeps its durable `DONE` flip — a cancelled or
 * failed re-run resumes exactly where it stopped.
 *
 * ## Known limitations (deliberate)
 *
 * - The attempt counter restarts at 1; the identity `session_id:fragment_index`
 *   is unchanged, which is what a deduping server keys on.
 * - No language is reported (the journal does not store one), so the polish
 *   pass runs without a source-language clause.
 * - Byte-identical audio exists only for fragments whose upload is still on
 *   disk — a fragment whose audio is gone fails the run with a clear message
 *   instead of sending something else.
 */
class JournalReplay(
    private val journal: FragmentJournal,
    private val runner: SttRequestRunner,
    private val configProvider: () -> SttProviderConfig?,
    /**
     * #185: called before each network request. The app uses it to YIELD to a
     * live dictation — a recovery re-run must never compete with the
     * latency-critical live path for the same provider. Default: no gate.
     */
    private val beforeFragment: suspend (fragmentIndex: Int) -> Unit = {}
) {

    /** [processed] of [total] fragments handled (spliced or replayed). */
    data class Progress(val processed: Int, val total: Int)

    /**
     * The joined transcript plus the counts the UI reports. [replayedCount] is
     * how many fragments actually went to the network.
     */
    data class ReplayResult(
        val text: String,
        val fragmentCount: Int,
        val replayedCount: Int
    )

    /**
     * Re-runs [sessionId]. Never throws for a provider/IO problem — the
     * `Result` carries a message that names the fragment.
     */
    suspend fun replay(
        sessionId: String,
        onProgress: (Progress) -> Unit = {}
    ): Result<ReplayResult> {
        val config = configProvider()
            ?: return Result.failure(IllegalStateException("STT provider not configured"))
        val records = journal.readRecords(sessionId)
        if (records.isEmpty()) {
            return Result.failure(IllegalStateException("no fragments to replay"))
        }

        val texts = HashMap<Int, String>()
        var replayed = 0
        // #117: a provider without prompt support rejects the field with HTTP
        // 400 — retry that fragment without it and stop sending prompts for the
        // rest of the run, exactly like the live worker.
        var promptDisabled = false

        records.forEachIndexed { position, record ->
            val stored = record.sttTrimmed?.takeIf { record.state == FragmentJournal.State.DONE.name }
            if (stored != null) {
                texts[record.index] = stored
                onProgress(Progress(position + 1, records.size))
                return@forEachIndexed
            }

            val upload = record.uploadFile?.let { File(journal.sessionDir(sessionId), it) }
            if (upload == null || !upload.isFile) {
                return Result.failure(
                    IllegalStateException("fragment ${record.index}: journal audio is missing")
                )
            }

            // The previous fragment's stored/replayed text is the echo-trim
            // anchor. Contiguous indices are the norm; using the previous
            // RECORD keeps a sparse session honest.
            val knownTail = records.getOrNull(position - 1)?.let { texts[it.index] }
            val prompt = if (promptDisabled || record.stitched == false) null else record.prompt

            beforeFragment(record.index)
            var attempt = runner.run(
                audioFile = upload,
                config = config,
                chunk = record.index + 1,
                prompt = prompt,
                sessionId = sessionId,
                fragmentIndex = record.index
            )
            if (attempt.isFailure && prompt != null &&
                (attempt.exceptionOrNull() as? SttRequestRunner.SttRequestException)?.httpCode == 400
            ) {
                promptDisabled = true
                attempt = runner.run(
                    audioFile = upload,
                    config = config,
                    chunk = record.index + 1,
                    sessionId = sessionId,
                    fragmentIndex = record.index
                )
            }

            val response = attempt.getOrElse { error ->
                val failure = error as? SttRequestRunner.SttRequestException
                    ?: SttRequestRunner.SttRequestException(
                        error.message ?: error.javaClass.simpleName
                    )
                journal.markFailed(
                    sessionId = sessionId,
                    index = record.index,
                    error = failure.message,
                    needsManual = failure.failure == SttFailure.PROTOCOL,
                    failureClass = failure.failure?.name,
                    httpCode = failure.httpCode
                )
                return Result.failure(
                    IllegalStateException("fragment ${record.index}: ${failure.message}", failure)
                )
            }

            val echo = EchoTrim.trim(
                text = response.text,
                knownTail = knownTail,
                hadPreRoll = record.preRollMs != null
            )
            val storedText = if (record.seamForced == true) {
                LoopGuard.collapse(echo.text)
            } else {
                echo.text
            }
            if (!journal.markDone(sessionId, record.index, response.text, storedText)) {
                CoreLog.w(TAG, "replay: journal outcome write failed (index=${record.index})")
            }
            texts[record.index] = storedText
            replayed++
            onProgress(Progress(position + 1, records.size))
        }

        val joined = records
            .mapNotNull { texts[it.index] }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return Result.success(
            ReplayResult(text = joined, fragmentCount = records.size, replayedCount = replayed)
        )
    }

    private companion object {
        const val TAG = "JournalReplay"
    }
}
