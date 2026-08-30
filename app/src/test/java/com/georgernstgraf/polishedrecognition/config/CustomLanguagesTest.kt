package com.georgernstgraf.polishedrecognition.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CustomLanguagesTest {

    @Test
    fun `displayList starts with built-ins then sorted customs`() {
        val list = CustomLanguages.displayList(listOf("Italian", "German"))
        assertThat(list).containsExactly("None (no translation)", "English", "German", "Italian").inOrder()
    }

    @Test
    fun `displayList with no customs is just the built-ins`() {
        val list = CustomLanguages.displayList(emptyList())
        assertThat(list).containsExactly("None (no translation)", "English").inOrder()
    }

    @Test
    fun `isBuiltIn matches None and English`() {
        assertThat(CustomLanguages.isBuiltIn("None (no translation)")).isTrue()
        assertThat(CustomLanguages.isBuiltIn("English")).isTrue()
        assertThat(CustomLanguages.isBuiltIn("German")).isFalse()
    }

    @Test
    fun `rename replaces only the old entry in place`() {
        val languages = listOf("German", "French", "Italian")
        val renamed = CustomLanguages.rename(languages, "French", "Spanish")
        assertThat(renamed).containsExactly("German", "Spanish", "Italian").inOrder()
    }

    @Test
    fun `rename of unknown entry leaves list unchanged`() {
        val languages = listOf("German", "French")
        val renamed = CustomLanguages.rename(languages, "Italian", "Spanish")
        assertThat(renamed).containsExactly("German", "French").inOrder()
    }

    @Test
    fun `validateRename rejects empty name`() {
        assertThat(CustomLanguages.validateRename(listOf("German"), "German", "  "))
            .isEqualTo(CustomLanguages.RenameError.EMPTY)
    }

    @Test
    fun `validateRename rejects existing name ignoring case`() {
        assertThat(CustomLanguages.validateRename(listOf("German", "French"), "German", "FRENCH"))
            .isEqualTo(CustomLanguages.RenameError.DUPLICATE)
        assertThat(CustomLanguages.validateRename(listOf("French"), "German", "FRENCH"))
            .isEqualTo(CustomLanguages.RenameError.DUPLICATE)
    }

    @Test
    fun `validateRename rejects built-in names`() {
        assertThat(CustomLanguages.validateRename(listOf("German"), "German", "English"))
            .isEqualTo(CustomLanguages.RenameError.DUPLICATE)
        assertThat(CustomLanguages.validateRename(listOf("German"), "German", "None (no translation)"))
            .isEqualTo(CustomLanguages.RenameError.DUPLICATE)
    }

    @Test
    fun `validateRename allows case-only change of the same entry`() {
        assertThat(CustomLanguages.validateRename(listOf("German"), "German", "GERMAN")).isNull()
    }

    @Test
    fun `validateRename allows unchanged name as no-op`() {
        assertThat(CustomLanguages.validateRename(listOf("German"), "German", "German")).isNull()
    }

    @Test
    fun `validateRename allows unused name`() {
        assertThat(CustomLanguages.validateRename(listOf("German"), "German", "Italian")).isNull()
    }

    @Test
    fun `commitEdit selects existing entry case-insensitively`() {
        val result = CustomLanguages.commitEdit(listOf("German", "French"), "  FRENCH  ")
        assertThat(result).isEqualTo(CustomLanguages.CommitResult.Selected("French"))
    }

    @Test
    fun `commitEdit selects built-in entries`() {
        assertThat(CustomLanguages.commitEdit(listOf("German"), "english"))
            .isEqualTo(CustomLanguages.CommitResult.Selected("English"))
        assertThat(CustomLanguages.commitEdit(listOf("German"), "none (no translation)"))
            .isEqualTo(CustomLanguages.CommitResult.Selected("None (no translation)"))
    }

    @Test
    fun `commitEdit appends new name and returns updated list`() {
        val result = CustomLanguages.commitEdit(listOf("German"), "  Italian  ")
        assertThat(result).isEqualTo(CustomLanguages.CommitResult.Added("Italian", listOf("German", "Italian")))
    }

    @Test
    fun `commitEdit appends new name to empty list`() {
        val result = CustomLanguages.commitEdit(emptyList(), "German")
        assertThat(result).isEqualTo(CustomLanguages.CommitResult.Added("German", listOf("German")))
    }

    @Test
    fun `commitEdit reverts on blank input`() {
        assertThat(CustomLanguages.commitEdit(listOf("German"), "   "))
            .isEqualTo(CustomLanguages.CommitResult.Reverted)
        assertThat(CustomLanguages.commitEdit(emptyList(), ""))
            .isEqualTo(CustomLanguages.CommitResult.Reverted)
    }
}
