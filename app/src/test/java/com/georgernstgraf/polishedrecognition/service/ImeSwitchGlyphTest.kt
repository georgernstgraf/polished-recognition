package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * #217: the switch button reads as a SWITCH, not as a second discard — the
 * close glyph next to the trash invited exactly that misreading (the owner
 * read the X as "throw everything away", while #155 made it the keyboard
 * switch that pauses). Static XML guard in the `XmlColorPolicyTest` pattern:
 * pure JUnit, no Robolectric — the layout must carry the keyboard glyph on
 * `ime_switch_button`, and the close drawable must not come back.
 */
class ImeSwitchGlyphTest {

    private val layout: File = File(
        System.getProperty("user.dir") ?: ".",
        "src/main/res/layout/ime_voice_input.xml"
    )

    @Test
    fun `the switch button carries the keyboard glyph, not a close glyph`() {
        val src = layout.readLines()
            .filter { it.contains("ime_switch_button") || it.contains("android:src") }
        // the button block: the id line and its own android:src line
        val buttonIndex = src.indexOfFirst { it.contains("ime_switch_button") }
        assertThat(buttonIndex).isAtLeast(0)
        val srcLine = src.drop(buttonIndex + 1).first { it.contains("android:src") }
        assertThat(srcLine).contains("@drawable/ic_keyboard")
        assertThat(srcLine).doesNotContain("ic_close")
    }

    @Test
    fun `the dead close drawable stays dead in the IME bar`() {
        val text = layout.readText()
        assertThat(text).doesNotContain("@drawable/ic_close")
    }
}
