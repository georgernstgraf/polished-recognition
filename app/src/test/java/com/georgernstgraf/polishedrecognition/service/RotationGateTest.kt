package com.georgernstgraf.polishedrecognition.service

import com.georgernstgraf.polishedrecognition.service.RotationGate.FieldId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RotationGateTest {

    private val field = FieldId("com.example.notes", 123)

    @Test
    fun `restarting with fresh mark is rotation`() {
        assertThat(RotationGate.isFresh(1000L, 1000L + 500L)).isTrue()
    }

    @Test
    fun `mark at window edge is fresh`() {
        assertThat(RotationGate.isFresh(1000L, 1000L + RotationGate.FRESH_WINDOW_MS)).isTrue()
    }

    @Test
    fun `stale mark is not fresh`() {
        assertThat(
            RotationGate.isFresh(1000L, 1000L + RotationGate.FRESH_WINDOW_MS + 1L)
        ).isFalse()
    }

    @Test
    fun `absent mark is never fresh`() {
        assertThat(RotationGate.isFresh(0L, 9000L)).isFalse()
    }

    @Test
    fun `same package and fieldId is same field`() {
        assertThat(
            RotationGate.isSameField(field, FieldId("com.example.notes", 123))
        ).isTrue()
    }

    @Test
    fun `different package is not same field`() {
        assertThat(
            RotationGate.isSameField(field, FieldId("com.other.app", 123))
        ).isFalse()
    }

    @Test
    fun `different fieldId is not same field`() {
        assertThat(
            RotationGate.isSameField(field, FieldId("com.example.notes", 456))
        ).isFalse()
    }

    @Test
    fun `absent last field is never same field`() {
        assertThat(RotationGate.isSameField(null, field)).isFalse()
    }

    @Test
    fun `id-less field matches on package alone`() {
        assertThat(
            RotationGate.isSameField(FieldId("com.example.notes", 0), field)
        ).isTrue()
        assertThat(
            RotationGate.isSameField(field, FieldId("com.example.notes", 0))
        ).isTrue()
    }

    @Test
    fun `id-less field on other package is not same field`() {
        assertThat(
            RotationGate.isSameField(FieldId("com.other.app", 0), field)
        ).isFalse()
    }

    @Test
    fun `fresh mark on same field is rotation`() {
        assertThat(RotationGate.isRotation(1000L, 1500L, field, field)).isTrue()
    }

    @Test
    fun `fresh mark on other field is not rotation`() {
        assertThat(
            RotationGate.isRotation(
                1000L, 1500L, field, FieldId("com.example.notes", 456)
            )
        ).isFalse()
    }

    @Test
    fun `stale mark on same field is not rotation`() {
        assertThat(
            RotationGate.isRotation(
                1000L, 1000L + RotationGate.FRESH_WINDOW_MS + 1L, field, field
            )
        ).isFalse()
    }

    @Test
    fun `first start with fresh mark is not rotation`() {
        assertThat(RotationGate.isRotation(1000L, 1500L, null, field)).isFalse()
    }
}
