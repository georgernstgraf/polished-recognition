package com.georgernstgraf.polishedrecognition.ui.contrast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #161: unit tests for the WCAG arithmetic in [Contrast].
 *
 * Deliberately a plain JUnit test — no Robolectric runner. [Contrast] is written in plain bit
 * operations precisely so that it needs no Android runtime; the unit-test stub of
 * `android.graphics.Color` answers every static call with 0, which would make these numbers
 * meaningless. Keeping the runner off is also the proof that the helpers are reusable by the
 * pixel test (#162) and the colour-pair test (#163).
 */
class ContrastTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `black on white is the maximum ratio 21`() {
        assertThat(Contrast.contrastRatio(black, white)).isWithin(1e-9).of(21.0)
        assertThat(Contrast.contrastRatio(white, black)).isWithin(1e-9).of(21.0)
    }

    @Test
    fun `a colour against itself is 1`() {
        assertThat(Contrast.contrastRatio(white, white)).isWithin(1e-9).of(1.0)
        assertThat(Contrast.contrastRatio(black, black)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `mid grey 777777 on white is about 4_48`() {
        val ratio = Contrast.contrastRatio(0xFF777777.toInt(), white)
        assertThat(ratio).isWithin(0.01).of(4.48)
        // The WCAG floor for normal text — this grey is just short of it, which is why the
        // palette uses #666666 (5.05:1) as the tertiary colour.
        assertThat(ratio).isLessThan(4.5)
    }

    @Test
    fun `alpha compositing puts half-transparent black over white at the midpoint`() {
        val composite = Contrast.compositeOver(0x80000000.toInt(), white)
        assertThat(Contrast.alpha(composite)).isEqualTo(0xFF)
        // 0x80/0xFF is 0.50196, NOT exactly one half, so the exact source-over result is
        // 255 - 128 = 127 (#7F). The nominal midpoint #808080 is one step away.
        assertThat(Contrast.hex(composite)).isEqualTo("#FF7F7F7F")
        assertThat(Contrast.red(composite)).isIn(listOf(127, 128))
        assertThat(Contrast.green(composite)).isEqualTo(Contrast.red(composite))
        assertThat(Contrast.blue(composite)).isEqualTo(Contrast.red(composite))
    }

    @Test
    fun `compositing an opaque colour returns that colour`() {
        assertThat(Contrast.compositeOver(0xFF212121.toInt(), white)).isEqualTo(0xFF212121.toInt())
        assertThat(Contrast.compositeOver(white, black)).isEqualTo(white)
    }

    @Test
    fun `compositing onto a transparent background keeps the source alpha`() {
        val composite = Contrast.compositeOver(0x80000000.toInt(), Contrast.TRANSPARENT)
        assertThat(Contrast.hex(composite)).isEqualTo("#80000000")
    }

    @Test
    fun `large text starts at 18sp`() {
        assertThat(Contrast.isLargeText(18f, isBold = false, scaledDensity = 1f)).isTrue()
        assertThat(Contrast.isLargeText(17.9f, isBold = false, scaledDensity = 1f)).isFalse()
    }

    @Test
    fun `bold text is large from 14sp`() {
        assertThat(Contrast.isLargeText(14f, isBold = true, scaledDensity = 1f)).isTrue()
        assertThat(Contrast.isLargeText(13.9f, isBold = true, scaledDensity = 1f)).isFalse()
        // Not bold at 14sp is still normal text.
        assertThat(Contrast.isLargeText(14f, isBold = false, scaledDensity = 1f)).isFalse()
    }

    @Test
    fun `text size is converted through the scaled density`() {
        // 18sp at density 2.75 is 49.5px; the epsilon absorbs the float round-trip.
        assertThat(Contrast.isLargeText(49.5f, isBold = false, scaledDensity = 2.75f)).isTrue()
        // 48px / 2.75 = 17.45sp — below 18sp minus the epsilon.
        assertThat(Contrast.isLargeText(48f, isBold = false, scaledDensity = 2.75f)).isFalse()
        // A zero density is a broken configuration, never a crash.
        assertThat(Contrast.isLargeText(18f, isBold = false, scaledDensity = 0f)).isFalse()
    }

    @Test
    fun `threshold matrix`() {
        assertThat(Contrast.thresholdFor(large = false, disabled = false)).isEqualTo(4.5)
        assertThat(Contrast.thresholdFor(large = true, disabled = false)).isEqualTo(3.0)
        assertThat(Contrast.thresholdFor(large = false, disabled = true)).isEqualTo(3.0)
        assertThat(Contrast.thresholdFor(large = true, disabled = true)).isEqualTo(3.0)
    }

    @Test
    fun `channel accessors and hex keep the alpha byte`() {
        val colour = 0x80123456.toInt()
        assertThat(Contrast.alpha(colour)).isEqualTo(0x80)
        assertThat(Contrast.red(colour)).isEqualTo(0x12)
        assertThat(Contrast.green(colour)).isEqualTo(0x34)
        assertThat(Contrast.blue(colour)).isEqualTo(0x56)
        assertThat(Contrast.hex(colour)).isEqualTo("#80123456")
        assertThat(Contrast.argb(0x80, 0x12, 0x34, 0x56)).isEqualTo(colour)
    }
}
