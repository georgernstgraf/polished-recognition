package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecTimeFormatterTest {

    @Test
    fun `recording shows plain timer without REC prefix`() {
        assertThat(RecTimeFormatter.recording(0L)).isEqualTo("0:00")
        assertThat(RecTimeFormatter.recording(34_000L)).isEqualTo("0:34")
        assertThat(RecTimeFormatter.recording(65_000L)).isEqualTo("1:05")
    }

    @Test
    fun `paused shows frozen value without REC prefix`() {
        assertThat(RecTimeFormatter.paused(34_000L)).isEqualTo("0:34")
        assertThat(RecTimeFormatter.paused(0L)).isEqualTo("0:00")
    }

    @Test
    fun `sub-second durations truncate down`() {
        assertThat(RecTimeFormatter.recording(999L)).isEqualTo("0:00")
        assertThat(RecTimeFormatter.recording(59_999L)).isEqualTo("0:59")
    }

    @Test
    fun `hours fold into total minutes`() {
        assertThat(RecTimeFormatter.recording(3_600_000L)).isEqualTo("60:00")
    }

    @Test
    fun `negative input clamps to zero`() {
        assertThat(RecTimeFormatter.recording(-5_000L)).isEqualTo("0:00")
        assertThat(RecTimeFormatter.paused(-5_000L)).isEqualTo("0:00")
    }
}
