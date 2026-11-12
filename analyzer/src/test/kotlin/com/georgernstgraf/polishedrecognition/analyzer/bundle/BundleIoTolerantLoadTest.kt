package com.georgernstgraf.polishedrecognition.analyzer.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The corpus is REAL material, so the directory-wide load must survive a bad
 * bundle (2026-11-12, live on the opo corpus): 101 pre-#142 sessions whose pull
 * wrote adb's error text into `session.json` made `loadAll` throw, which killed
 * every `scan` of the 481-bundle corpus — the #129 natural-find detector could
 * not run at all. Unparseable bundles are now skipped and reported, never fatal.
 */
class BundleIoTolerantLoadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bundle(name: String, json: String): File =
        tmp.newFolder(name).apply { File(this, "session.json").writeText(json) }

    @Test
    fun `an unparseable bundle is skipped, the valid ones still load`() {
        bundle("20261013-2110-44bc6a60", "cat: /sdcard/Android/data/…/session.json: No such file or directory")
        bundle("20261105-1805-19643e09", """{"sessionId":"20261105-1805-19643e09","fragments":[]}""")
        bundle("20261107-1116-4eeb1da8", """{"sessionId":"20261107-1116-4eeb1da8","fragments":[]}""")

        val loaded = BundleIo.loadAll(tmp.root)

        assertEquals("only the two valid bundles load", 2, loaded.size)
        assertEquals(
            listOf("20261105-1805-19643e09", "20261107-1116-4eeb1da8"),
            loaded.map { it.second.sessionId },
        )
    }

    @Test
    fun `a directory without session json is not a bundle`() {
        tmp.newFolder("20261013-2110-44bc6a60").apply {
            File(this, "session.wav").writeBytes(byteArrayOf(0, 1, 2))
        }
        bundle("20261105-1805-19643e09", """{"sessionId":"20261105-1805-19643e09"}""")

        assertEquals(1, BundleIo.loadAll(tmp.root).size)
    }

    @Test
    fun `an all-bad directory yields an empty list instead of throwing`() {
        bundle("20261013-2110-44bc6a60", "cat: …: No such file or directory")

        assertTrue(BundleIo.loadAll(tmp.root).isEmpty())
    }
}
