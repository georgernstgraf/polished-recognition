package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #169 (epic #166 D6): the ◀ / ▶ bounds and the position label. */
class FifoNavigationTest {

    @Test
    fun `an empty fifo has no navigation and shows 0 of 0`() {
        assertThat(FifoNavigation.initialIndex(0)).isEqualTo(-1)
        assertThat(FifoNavigation.canGoOlder(-1, 0)).isFalse()
        assertThat(FifoNavigation.canGoNewer(-1, 0)).isFalse()
        assertThat(FifoNavigation.positionLabel(-1, 0)).isEqualTo("0 / 0")
    }

    @Test
    fun `a single dictation is its own start and end`() {
        assertThat(FifoNavigation.initialIndex(1)).isEqualTo(0)
        assertThat(FifoNavigation.canGoOlder(0, 1)).isFalse()
        assertThat(FifoNavigation.canGoNewer(0, 1)).isFalse()
        assertThat(FifoNavigation.positionLabel(0, 1)).isEqualTo("1 / 1")
    }

    @Test
    fun `older walks down, newer walks up, both stop at the ends`() {
        val size = 3
        var index = FifoNavigation.initialIndex(size)
        assertThat(index).isEqualTo(2)
        assertThat(FifoNavigation.positionLabel(index, size)).isEqualTo("3 / 3")

        index = FifoNavigation.older(index, size)
        assertThat(index).isEqualTo(1)
        assertThat(FifoNavigation.older(index, size)).isEqualTo(0)
        assertThat(FifoNavigation.older(0, size)).isEqualTo(0)

        assertThat(FifoNavigation.newer(0, size)).isEqualTo(1)
        assertThat(FifoNavigation.newer(2, size)).isEqualTo(2)
    }

    @Test
    fun `navigation never leaves the valid range`() {
        assertThat(FifoNavigation.older(-1, 3)).isEqualTo(-1)
        assertThat(FifoNavigation.newer(0, 0)).isEqualTo(0)
        assertThat(FifoNavigation.canGoOlder(5, 3)).isFalse()
        assertThat(FifoNavigation.canGoNewer(-1, 3)).isFalse()
    }
}
