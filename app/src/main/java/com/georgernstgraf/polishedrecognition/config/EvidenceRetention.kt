package com.georgernstgraf.polishedrecognition.config

/**
 * #142: the retention-horizon scale — a fixed, log-spaced stop list from
 * "no retention" (0) up to 50 years (effectively "never delete by age").
 * The Settings SeekBar indexes into [STOPS]; the label is always shown
 * dynamically next to the slider (the owner's design: slide 0 → 50 years,
 * the label tracks the position).
 *
 * Pure JVM + no Android imports, so the mapping is unit-testable and the
 * PC-side tooling can mirror it.
 */
object EvidenceRetention {

    /** (seconds, label) pairs; index 0 = off. Log-spaced onward. */
    val STOPS: List<Pair<Long, String>> = listOf(
        0L to "No retention",
        3_600L to "1 hour",
        21_600L to "6 hours",
        43_200L to "12 hours",
        86_400L to "1 day",
        172_800L to "2 days",
        345_600L to "4 days",
        604_800L to "1 week",
        1_209_600L to "2 weeks",
        2_592_000L to "1 month",
        7_776_000L to "3 months",
        15_552_000L to "6 months",
        31_536_000L to "1 year",
        63_072_000L to "2 years",
        157_680_000L to "5 years",
        315_360_000L to "10 years",
        788_400_000L to "25 years",
        1_576_800_000L to "50 years"
    )

    /** Highest slider position (= `SeekBar.max`). */
    val MAX_POSITION = STOPS.size - 1

    /** The stored seconds → slider position (the last stop ≤ the value). */
    fun positionFor(seconds: Long): Int {
        if (seconds <= 0L) return 0
        var position = 1
        for (i in 1 until STOPS.size) {
            if (STOPS[i].first <= seconds) position = i else break
        }
        return position
    }

    /** Slider position → the stored seconds. */
    fun secondsFor(position: Int): Long =
        STOPS[position.coerceIn(0, MAX_POSITION)].first

    /** The dynamic label for a position ("1 month", "50 years", "No retention"). */
    fun labelFor(position: Int): String =
        STOPS[position.coerceIn(0, MAX_POSITION)].second
}
