package com.georgernstgraf.polishedrecognition.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PcmConditionerTest {

    private fun pcmOf(samples: List<Short>): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            val v = s.toInt() and 0xFFFF
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samplesOf(pcm: ByteArray): List<Short> {
        val out = ArrayList<Short>(pcm.size / 2)
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() and 0xFF) shl 8) or (pcm[i].toInt() and 0xFF)
            out.add(if (v >= 32768) (v - 65536).toShort() else v.toShort())
            i += 2
        }
        return out
    }

    private fun sine(amplitude: Short, periods: Int, count: Int): List<Short> {
        val step = 2.0 * Math.PI * periods / count
        return (0 until count).map { (amplitude * Math.sin(it * step)).toInt().toShort() }
    }

    private fun peakOf(samples: List<Short>): Int =
        samples.maxOf { if (it < 0) -it.toInt() else it.toInt() }

    @Test
    fun `quiet signal is amplified to target peak`() {
        val pcm = pcmOf(sine(5000, 250, 8000)) // 500 Hz, 0.5 s at 16 kHz
        val out = samplesOf(PcmConditioner.condition(pcm, 16000))
        assertThat(peakOf(out)).isWithin(60).of(26214)
    }

    @Test
    fun `loud signal is not attenuated`() {
        val pcm = pcmOf(sine(32000, 250, 8000))
        val out = samplesOf(PcmConditioner.condition(pcm, 16000))
        assertThat(peakOf(out)).isAtLeast(31500)
    }

    @Test
    fun `silence is left untouched`() {
        val pcm = pcmOf(List(4000) { 0.toShort() })
        val out = samplesOf(PcmConditioner.condition(pcm, 16000))
        assertThat(peakOf(out)).isEqualTo(0)
    }

    @Test
    fun `near-silence is not amplified`() {
        val pcm = pcmOf(sine(100, 250, 8000))
        val out = samplesOf(PcmConditioner.condition(pcm, 16000))
        assertThat(peakOf(out)).isWithin(60).of(100)
    }

    @Test
    fun `deep negative samples survive round trip without wraparound`() {
        val pcm = pcmOf(listOf(-32768, -30000, 30000, 32767).map { it.toShort() })
        val out = samplesOf(PcmConditioner.condition(pcm, 16000))
        // the first-sample transient passes the one-pole filter slightly attenuated,
        // but must never wrap around to the positive half (the sign-correction bug)
        assertThat(peakOf(out)).isAtLeast(31000)
        assertThat(peakOf(out)).isAtMost(32767)
    }

    @Test
    fun `dc offset is removed by high pass`() {
        val pcm = pcmOf(List(8000) { 5000.toShort() })
        val out = samplesOf(PcmConditioner.condition(pcm, 16000))
        // mean collapses from 5000 to quantization residue (normalization may
        // amplify the decaying transient, so a loose bound is the right assertion)
        assertThat(out.average()).isAtMost(200.0)
        assertThat(out.last()).isEqualTo(0)
    }

    @Test
    fun `odd-length input is trimmed to whole samples`() {
        val out = PcmConditioner.condition(ByteArray(5), 16000)
        assertThat(out.size).isEqualTo(4)
    }

    @Test
    fun `empty input returns empty output`() {
        assertThat(PcmConditioner.condition(ByteArray(0), 16000)).isEmpty()
    }
}
