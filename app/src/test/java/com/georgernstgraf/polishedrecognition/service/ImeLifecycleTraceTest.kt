package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImeLifecycleTraceTest {

    private fun logFile(): File {
        val ctx = RuntimeEnvironment.getApplication()
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs")
        return File(dir, "ime-lifecycle.log")
    }

    @Test
    fun `events are appended with pid`() {
        logFile().delete()
        val ctx = RuntimeEnvironment.getApplication()
        ImeLifecycleTrace.log(ctx, "onCreate", "state=PAUSED restored=true")
        ImeLifecycleTrace.log(ctx, "onDestroy", "state=PAUSED")

        val lines = logFile().readLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("pid=")
        assertThat(lines[0]).contains("onCreate state=PAUSED restored=true")
        assertThat(lines[1]).contains("onDestroy state=PAUSED")
    }

    @Test
    fun `log is capped`() {
        logFile().delete()
        val ctx = RuntimeEnvironment.getApplication()
        repeat(210) { ImeLifecycleTrace.log(ctx, "tick", "n=$it") }

        assertThat(logFile().readLines().size).isAtMost(200)
    }
}
