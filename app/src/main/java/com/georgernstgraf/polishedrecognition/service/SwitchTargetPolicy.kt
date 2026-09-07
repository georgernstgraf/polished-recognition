package com.georgernstgraf.polishedrecognition.service

/**
 * Decides what the keyboard-switch button should do: switch to the last-used
 * keyboard with keys, or send the user to the system screen where keyboards can
 * be enabled when no such keyboard is available.
 */
object SwitchTargetPolicy {

    data class EnabledIme(val id: String, val hasKeys: Boolean)

    /**
     * Parses the raw `input_methods_subtype_history` secure setting into the ordered
     * list of IME ids it contains (most recently used first). Each history entry has
     * the form `imeId;subtypeHash`; entries are separated by `:`. Malformed entries
     * yield an id that simply never matches an enabled IME.
     */
    fun parseHistory(raw: String?): List<String> =
        raw?.split(':')?.map { it.substringBefore(';') }?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * The last-used enabled IME that has keys and is not ourselves, or null when the
     * user must be sent to the keyboard settings screen instead.
     */
    fun targetKeyboard(history: List<String>, enabled: List<EnabledIme>, selfId: String): String? {
        val enabledById = enabled.associateBy { it.id }
        return history.firstOrNull { id -> id != selfId && enabledById[id]?.hasKeys == true }
    }
}
