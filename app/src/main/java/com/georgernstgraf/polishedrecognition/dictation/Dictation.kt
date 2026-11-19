package com.georgernstgraf.polishedrecognition.dictation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * One Workbench dictation (#167, epic #166): a raw transcript plus at most one
 * polished transcript. The vocabulary is shared with `diktatwerkstatt`
 * (`CONTEXT.md`) — a dictation is what the FIFO holds, not a session and not a
 * fragment.
 *
 * [polished] is `null` until a polish arrives; a raw edit discards it
 * (inherited desktop rule, enforced by [DictationStore.updateRaw]).
 *
 * [id] is the file stem: a LOCAL-time prefix plus 8 hex characters, the same
 * shape as a session id (`yyyyMMdd-HHmm-<8hex>`) so a directory listing is
 * recognisable by dictation date/time.
 */
data class Dictation(
    val id: String,
    val raw: String,
    val polished: String? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

/** Local-time-prefixed, path-safe dictation id (#167, mirrors `newSessionId`). */
internal fun newDictationId(now: Date, random: Random = Random.Default): String {
    val prefix = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)
    val suffix = (1..8).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
    return "$prefix-$suffix"
}
