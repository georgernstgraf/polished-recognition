package com.georgernstgraf.polishedrecognition.analyzer.corpus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pull` must not drag pre-#142 sessions into a corpus (2026-11-12): they carry
 * `session.wav` + `fragments/` but no `session.json`, so every scan has to skip
 * them — and the old pull wrote adb's "No such file" text into a 140-byte
 * `session.json` instead, poisoning the corpus.
 */
class PullBundleFilterTest {

    @Test
    fun `a listing with session json is a bundle`() {
        assertTrue(PullCommand.isBundleListing(listOf("uploads/", "session.json")))
    }

    @Test
    fun `a pre-142 listing without session json is not a bundle`() {
        assertFalse(PullCommand.isBundleListing(listOf("fragments/", "session.wav")))
    }

    @Test
    fun `an empty or failed listing is not a bundle`() {
        assertFalse(PullCommand.isBundleListing(emptyList()))
    }

    @Test
    fun `a directory named session json does not count`() {
        assertFalse(PullCommand.isBundleListing(listOf("session.json/")))
    }
}
