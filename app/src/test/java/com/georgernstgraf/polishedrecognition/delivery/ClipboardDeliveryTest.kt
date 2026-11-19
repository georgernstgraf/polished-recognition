package com.georgernstgraf.polishedrecognition.delivery

import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #170 (epic #166 D11/D12): the clipboard stays sensitive and empty text never overwrites. */
@RunWith(RobolectricTestRunner::class)
class ClipboardDeliveryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)

    private fun extras() = clipboard().primaryClip?.description?.extras

    @Test
    fun `the text lands on the clipboard`() {
        val delivered = ClipboardDelivery.deliver(context, "hello", "Workbench")

        assertThat(delivered).isTrue()
        assertThat(clipboard().primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("hello")
    }

    @Test
    @Config(sdk = [34])
    fun `the clip is marked sensitive on api 33 and newer`() {
        ClipboardDelivery.deliver(context, "client dictation", "Workbench")

        val extras = extras()
        assertThat(extras).isNotNull()
        assertThat(extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)).isTrue()
    }

    @Test
    @Config(sdk = [30])
    fun `below api 33 the delivery still works without the flag`() {
        val delivered = ClipboardDelivery.deliver(context, "old device", "Workbench")

        assertThat(delivered).isTrue()
        assertThat(clipboard().primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("old device")
        // No protection exists below 33 (#166 D12, accepted) — the mechanism
        // must simply not break.
        assertThat(Build.VERSION.SDK_INT).isEqualTo(30)
        assertThat(extras()?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false).isFalse()
    }

    @Test
    fun `an empty text never overwrites the clipboard`() {
        ClipboardDelivery.deliver(context, "keep me", "Workbench")

        val delivered = ClipboardDelivery.deliver(context, "", "Workbench")

        assertThat(delivered).isFalse()
        assertThat(clipboard().primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("keep me")
    }
}
