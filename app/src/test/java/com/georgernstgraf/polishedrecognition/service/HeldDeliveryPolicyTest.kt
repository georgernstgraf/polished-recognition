package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.service.RotationGate.FieldId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * #207: the held-delivery matrix. A polished result that could not be
 * delivered at Send (field unreadable) is held; this policy decides what
 * every later readable moment does with it. The #192 target rule is the
 * core: the ORIGIN identity decides, an unknown origin is a foreign field,
 * and a foreign field is salvaged — never blind-inserted.
 */
class HeldDeliveryPolicyTest {

    private val origin = FieldId("com.example.chat", 1)
    private val same = FieldId("com.example.chat", 1)
    private val other = FieldId("com.example.chat", 2)
    private val otherApp = FieldId("com.example.notes", 1)

    @Test
    fun `an unreadable binding keeps the hold`() {
        assertThat(HeldDeliveryPolicy.decide(origin, same, readable = false))
            .isEqualTo(HeldDeliveryPolicy.Action.KEEP)
    }

    @Test
    fun `a readable bind of the origin field delivers`() {
        assertThat(HeldDeliveryPolicy.decide(origin, same, readable = true))
            .isEqualTo(HeldDeliveryPolicy.Action.DELIVER)
    }

    @Test
    fun `a readable bind of another field in the same app salvages`() {
        assertThat(HeldDeliveryPolicy.decide(origin, other, readable = true))
            .isEqualTo(HeldDeliveryPolicy.Action.SALVAGE)
    }

    @Test
    fun `a readable bind in another app salvages`() {
        assertThat(HeldDeliveryPolicy.decide(origin, otherApp, readable = true))
            .isEqualTo(HeldDeliveryPolicy.Action.SALVAGE)
    }

    /** #192 rule (c): no stored origin never means "any field". */
    @Test
    fun `an unknown origin salvages instead of inserting into any field`() {
        assertThat(HeldDeliveryPolicy.decide(null, same, readable = true))
            .isEqualTo(HeldDeliveryPolicy.Action.SALVAGE)
        assertThat(HeldDeliveryPolicy.decide(null, other, readable = true))
            .isEqualTo(HeldDeliveryPolicy.Action.SALVAGE)
    }

    /** The id-less-field match of [RotationGate.isSameField] carries over. */
    @Test
    fun `id-less fields match on the package alone`() {
        assertThat(
            HeldDeliveryPolicy.decide(
                FieldId("com.example.chat", 0),
                FieldId("com.example.chat", 7),
                readable = true
            )
        ).isEqualTo(HeldDeliveryPolicy.Action.DELIVER)
    }
}
