package com.georgernstgraf.polishedrecognition.recovery

import android.content.Intent
import android.net.Uri
import com.georgernstgraf.polishedrecognition.pipeline.FragmentJournal
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #188: the share intent shape — one file is a SEND, several are a
 * SEND_MULTIPLE, nothing is no intent at all.
 *
 * The extras are read through the plain `Bundle.get` shape on purpose:
 * `EXTRA_STREAM`'s typed accessors are API 33+ and the app still serves
 * API 30, so the shape under test is the one every device sees.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class RecoveryShareTest {

    private fun uri(path: String): Uri = Uri.parse("content://pkg.recovery/$path")

    @Test
    fun `one uri becomes a plain send with a read grant`() {
        val intent = RecoveryShare.intent(listOf(uri("one.wav")))!!

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("audio/*")
        assertThat(intent.extras!!.get(Intent.EXTRA_STREAM) as? Uri)
            .isEqualTo(uri("one.wav"))
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `several uris become a multiple send with every piece`() {
        val uris = listOf(uri("a.ogg"), uri("b.ogg"))

        val intent = RecoveryShare.intent(uris)!!

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(intent.type).isEqualTo("audio/*")
        assertThat(intent.extras!!.get(Intent.EXTRA_STREAM) as? List<*>)
            .containsExactlyElementsIn(uris)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `no uri means no intent`() {
        assertThat(RecoveryShare.intent(emptyList())).isNull()
    }

    @Test
    fun `a session without audio yields no files`() {
        val files = RecoveryShare.files(
            sessionId = "20260101-1200-aaaaaaaa",
            sessionDir = File("does-not-exist"),
            records = listOf(FragmentJournal.FragmentRecord(index = 0)),
            cacheDir = File("cache")
        )

        assertThat(files).isEmpty()
    }
}
