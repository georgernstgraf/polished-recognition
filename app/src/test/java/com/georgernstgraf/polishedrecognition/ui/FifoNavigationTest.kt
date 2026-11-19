package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #169 (epic #166 D6) + #173: the ring, the compose slot and the position label. */
class FifoNavigationTest {

    private val compose = FifoNavigation.COMPOSE_INDEX

    @Test
    fun `an empty store shows the compose slot and cannot navigate`() {
        assertThat(FifoNavigation.initialIndex()).isEqualTo(compose)
        assertThat(FifoNavigation.canNavigate(0)).isFalse()
        assertThat(FifoNavigation.older(compose, 0)).isEqualTo(compose)
        assertThat(FifoNavigation.newer(compose, 0)).isEqualTo(compose)
        assertThat(FifoNavigation.positionLabel(compose, 0)).isEqualTo("new")
    }

    @Test
    fun `both arrows leave the compose slot at the newest entry`() {
        val size = 3

        assertThat(FifoNavigation.older(compose, size)).isEqualTo(2)
        assertThat(FifoNavigation.newer(compose, size)).isEqualTo(2)
    }

    @Test
    fun `a single dictation is a ring of one that wraps onto itself`() {
        assertThat(FifoNavigation.canNavigate(1)).isTrue()
        assertThat(FifoNavigation.older(0, 1)).isEqualTo(0)
        assertThat(FifoNavigation.newer(0, 1)).isEqualTo(0)
        assertThat(FifoNavigation.positionLabel(0, 1)).isEqualTo("1 / 1")
    }

    @Test
    fun `the ring wraps in both directions`() {
        val size = 3

        assertThat(FifoNavigation.older(2, size)).isEqualTo(1)
        assertThat(FifoNavigation.older(1, size)).isEqualTo(0)
        assertThat(FifoNavigation.older(0, size)).isEqualTo(2) // oldest → newest

        assertThat(FifoNavigation.newer(0, size)).isEqualTo(1)
        assertThat(FifoNavigation.newer(1, size)).isEqualTo(2)
        assertThat(FifoNavigation.newer(2, size)).isEqualTo(0) // newest → oldest
    }

    @Test
    fun `the compose slot is outside the ring - no arrow can reach it`() {
        val size = 4
        for (index in 0 until size) {
            assertThat(FifoNavigation.older(index, size)).isNotEqualTo(compose)
            assertThat(FifoNavigation.newer(index, size)).isNotEqualTo(compose)
        }
    }

    @Test
    fun `the label counts inside the ring and says new on the compose slot`() {
        assertThat(FifoNavigation.positionLabel(compose, 3)).isEqualTo("new")
        assertThat(FifoNavigation.positionLabel(0, 3)).isEqualTo("1 / 3")
        assertThat(FifoNavigation.positionLabel(2, 3)).isEqualTo("3 / 3")
    }
}
