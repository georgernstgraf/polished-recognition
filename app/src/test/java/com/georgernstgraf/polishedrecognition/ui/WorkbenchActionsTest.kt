package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** #178: which Workbench controls are usable on a given page. */
class WorkbenchActionsTest {

    private val bools = listOf(false, true)

    private val ints = listOf(0, 1, 2)

    private fun enabled(
        hasRaw: Boolean = false,
        hasPolished: Boolean = false,
        polishing: Boolean = false,
        ringSize: Int = 0
    ) = WorkbenchActions.enabled(hasRaw, hasPolished, polishing, ringSize)

    @Test
    fun `the empty compose slot offers only new`() {
        assertThat(enabled()).isEqualTo(
            WorkbenchActions.Actions(
                polish = false,
                promote = false,
                clearPolished = false,
                copyRaw = false,
                copyPolished = false,
                prev = false,
                next = false,
                new = true
            )
        )
    }

    @Test
    fun `a stored raw dictation can be polished, copied and navigated`() {
        val actions = enabled(hasRaw = true, ringSize = 3)

        assertThat(actions.polish).isTrue()
        assertThat(actions.copyRaw).isTrue()
        assertThat(actions.prev).isTrue()
        assertThat(actions.next).isTrue()
        assertThat(actions.new).isTrue()
        assertThat(actions.promote).isFalse()
        assertThat(actions.clearPolished).isFalse()
        assertThat(actions.copyPolished).isFalse()
    }

    @Test
    fun `a filled polished slot enables every polished control`() {
        val actions = enabled(hasRaw = true, hasPolished = true, ringSize = 1)

        assertThat(actions.promote).isTrue()
        assertThat(actions.clearPolished).isTrue()
        assertThat(actions.copyPolished).isTrue()
    }

    @Test
    fun `a running polish never re-enables the polish control`() {
        val actions = enabled(hasRaw = true, polishing = true, ringSize = 2)

        assertThat(actions.polish).isFalse()
        assertThat(actions.copyRaw).isTrue()
        assertThat(actions.prev).isTrue()
        assertThat(actions.next).isTrue()
    }

    @Test
    fun `both arrows need a ring of at least two`() {
        assertThat(enabled(hasRaw = true, ringSize = 0).prev).isFalse()
        assertThat(enabled(hasRaw = true, ringSize = 1).prev).isFalse()
        assertThat(enabled(hasRaw = true, ringSize = 1).next).isFalse()
        assertThat(enabled(hasRaw = true, ringSize = 2).prev).isTrue()
        assertThat(enabled(hasRaw = true, ringSize = 2).next).isTrue()
    }

    @Test
    fun `polished controls never depend on raw text`() {
        val actions = enabled(hasRaw = false, hasPolished = true, ringSize = 2)

        assertThat(actions.polish).isFalse()
        assertThat(actions.copyRaw).isFalse()
        assertThat(actions.promote).isTrue()
        assertThat(actions.clearPolished).isTrue()
        assertThat(actions.copyPolished).isTrue()
    }

    @Test
    fun `new is enabled on every page of the truth table`() {
        for (hasRaw in bools) for (hasPolished in bools) for (polishing in bools) {
            for (ringSize in ints) {
                assertThat(enabled(hasRaw, hasPolished, polishing, ringSize).new).isTrue()
            }
        }
    }

    /**
     * The whole truth table (2 × 2 × 2 × 3 = 24 rows): every combination of
     * raw text, polished text, running polish and ring size.
     */
    @Test
    fun `the whole truth table follows the documented rules`() {
        for (hasRaw in bools) {
            for (hasPolished in bools) {
                for (polishing in bools) {
                    for (ringSize in ints) {
                        val actions = enabled(hasRaw, hasPolished, polishing, ringSize)
                        val where = "hasRaw=$hasRaw hasPolished=$hasPolished " +
                            "polishing=$polishing ringSize=$ringSize"

                        assertWithMessage("polish, $where").that(actions.polish)
                            .isEqualTo(hasRaw && !polishing)
                        assertWithMessage("promote, $where").that(actions.promote)
                            .isEqualTo(hasPolished)
                        assertWithMessage("clearPolished, $where").that(actions.clearPolished)
                            .isEqualTo(hasPolished)
                        assertWithMessage("copyRaw, $where").that(actions.copyRaw)
                            .isEqualTo(hasRaw)
                        assertWithMessage("copyPolished, $where").that(actions.copyPolished)
                            .isEqualTo(hasPolished)
                        assertWithMessage("prev, $where").that(actions.prev)
                            .isEqualTo(ringSize >= 2)
                        assertWithMessage("next, $where").that(actions.next)
                            .isEqualTo(ringSize >= 2)
                        assertWithMessage("new, $where").that(actions.new).isTrue()
                    }
                }
            }
        }
    }
}
