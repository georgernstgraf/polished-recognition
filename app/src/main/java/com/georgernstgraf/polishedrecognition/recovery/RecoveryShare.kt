package com.georgernstgraf.polishedrecognition.recovery

import android.content.Intent
import android.net.Uri
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import java.io.File

/**
 * #188: "Share audio" — the half of #185's recovery screen that hands the
 * session's audio to an arbitrary share target.
 *
 * The audio itself is [RecoveryAudio]'s business (one reconstructed WAV with
 * the pre-rolls cut, or the fragment files of an OGG session the app cannot
 * decode); this object owns the *file selection* and the *intent shape*, which
 * is the part worth pinning in a test:
 *
 * - one file ⇒ `ACTION_SEND` with `EXTRA_STREAM`;
 * - several ⇒ `ACTION_SEND_MULTIPLE` with the list;
 * - nothing ⇒ `null` (the caller says "no audio left" — never an empty send).
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` is mandatory: the files live under private
 * app directories and are only reachable through the `FileProvider`
 * (`<pkg>.recovery`) for the one chooser that received the grant.
 */
object RecoveryShare {

    /**
     * The files to attach: the reconstructed WAV written into
     * `cacheDir/<SHARE_DIR>/`, or the fragment files when no single stream can
     * be built. Empty when there is no audio left.
     */
    fun files(
        sessionId: String,
        sessionDir: File,
        records: List<FragmentJournal.FragmentRecord>,
        cacheDir: File
    ): List<File> {
        val wav = RecoveryAudio.reconstructWav(sessionDir, records)
        if (wav == null) return RecoveryAudio.pieceFiles(sessionDir, records)
        val shareDir = File(cacheDir, RecoveryAudio.SHARE_DIR).apply { mkdirs() }
        val out = File(shareDir, "$sessionId.wav")
        return try {
            out.writeBytes(wav)
            listOf(out)
        } catch (_: Throwable) {
            // A cache we cannot write is not a reason to lose the share: fall
            // back to the pieces, which the provider can serve as they are.
            RecoveryAudio.pieceFiles(sessionDir, records)
        }
    }

    /**
     * One content URI for [file], or `null` when the provider cannot serve it —
     * the caller drops that piece instead of failing the whole share.
     */
    fun uriOf(file: File, toUri: (File) -> Uri): Uri? =
        try {
            toUri(file)
        } catch (_: Throwable) {
            null
        }

    /** The send intent for [uris]; `null` when there is nothing to attach. */
    fun intent(uris: List<Uri>): Intent? = when (uris.size) {
        0 -> null
        1 -> Intent(Intent.ACTION_SEND).apply {
            type = AUDIO
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = AUDIO
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** The MIME type every recovery share declares (the pieces may be OGG). */
    const val AUDIO = "audio/*"
}
