package com.georgernstgraf.polishedrecognition.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InsertionSpacingPolicyTest {

    @Test
    fun `no leading space at genuine field start`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "", after = " bar")
        ).isEqualTo("foo")
    }

    @Test
    fun `genuine empty field keeps only the trailing space`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "", after = "")
        ).isEqualTo("foo ")
    }

    @Test
    fun `adds trailing space at field end`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar ", after = "")
        ).isEqualTo("foo ")
    }

    @Test
    fun `adds both spaces in mid-text between letters`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar", after = "baz")
        ).isEqualTo(" foo ")
    }

    @Test
    fun `adds leading space after punctuation`() {
        for (p in listOf('.', ',', '?', ';', ')')) {
            assertThat(
                InsertionSpacingPolicy.apply("foo", before = "bar$p", after = "baz")
            ).isEqualTo(" foo ")
        }
    }

    @Test
    fun `no leading space after existing space`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar ", after = "baz")
        ).isEqualTo("foo ")
    }

    @Test
    fun `no leading space after newline`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar\n", after = "baz")
        ).isEqualTo("foo ")
    }

    @Test
    fun `no leading space after tab`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar\t", after = "baz")
        ).isEqualTo("foo ")
    }

    @Test
    fun `no trailing space before existing space`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar", after = " baz")
        ).isEqualTo(" foo")
    }

    @Test
    fun `no trailing space before newline`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar", after = "\nbaz")
        ).isEqualTo(" foo")
    }

    @Test
    fun `null before keeps the conservative leading space`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = null, after = " baz")
        ).isEqualTo(" foo")
    }

    @Test
    fun `null after counts as no whitespace`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar ", after = null)
        ).isEqualTo("foo ")
    }

    @Test
    fun `text with own leading whitespace is not doubled`() {
        assertThat(
            InsertionSpacingPolicy.apply(" foo", before = "bar", after = "baz")
        ).isEqualTo(" foo ")
    }

    @Test
    fun `text with own trailing whitespace is not doubled`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo ", before = "bar", after = "baz")
        ).isEqualTo(" foo ")
    }

    @Test
    fun `empty text is returned unchanged`() {
        assertThat(
            InsertionSpacingPolicy.apply("", before = "bar", after = "baz")
        ).isEmpty()
    }

    @Test
    fun `no leading space after nbsp`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar\u00A0", after = "baz")
        ).isEqualTo("foo ")
    }

    @Test
    fun `no trailing space before nbsp`() {
        assertThat(
            InsertionSpacingPolicy.apply("foo", before = "bar", after = "\u00A0baz")
        ).isEqualTo(" foo")
    }
}
