package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #168 (epic #166 D7): the caller-package rule that forces raw. */
class LlmTargetPolicyTest {

    private val own = "com.georgernstgraf.polishedrecognition"

    @Test
    fun `our own package forces raw`() {
        assertThat(LlmTargetPolicy.forcesRaw(own, own)).isTrue()
    }

    @Test
    fun `a foreign package keeps the configured behaviour`() {
        assertThat(LlmTargetPolicy.forcesRaw("org.markor", own)).isFalse()
        assertThat(LlmTargetPolicy.forcesRaw("com.example.chat", own)).isFalse()
    }

    @Test
    fun `an unresolvable caller never forces anything`() {
        assertThat(LlmTargetPolicy.forcesRaw(null, own)).isFalse()
        assertThat(LlmTargetPolicy.forcesRaw("", own)).isFalse()
        assertThat(LlmTargetPolicy.forcesRaw("   ", own)).isFalse()
    }

    @Test
    fun `the rule is an exact package match, not a prefix`() {
        assertThat(LlmTargetPolicy.forcesRaw("$own.debug", own)).isFalse()
        assertThat(LlmTargetPolicy.forcesRaw(own, "$own.debug")).isFalse()
    }
}
