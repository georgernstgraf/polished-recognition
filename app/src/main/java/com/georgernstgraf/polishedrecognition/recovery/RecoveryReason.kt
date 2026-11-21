package com.georgernstgraf.polishedrecognition.recovery

/**
 * #185: turns a journal session's recorded failure into the one reason the
 * recovery UI shows — a pure mapping over the #183 classes, so the screen
 * never invents a rule of its own.
 *
 * The classes come from `SttFailure.name` (the journal stores the enum name):
 * `TRANSIENT`, `AUTH`, `PROTOCOL`, `TERMINAL`. A session without a recorded
 * class was interrupted (kill, reboot, flat battery) — not a provider answer.
 */
object RecoveryReason {

    enum class Kind {
        /** Finished and delivered, but the journal dir survived (a failed delete). */
        COMPLETED,

        /** 401/403 — the token is dead, waiting cannot heal it. */
        AUTH,

        /** A 2xx without a usable transcript — needs a manual decision. */
        PROTOCOL,

        /** Timeout, 408/429/5xx, connection loss — retrying can work. */
        NETWORK,

        /** Any other 4xx or an unexpected exception. */
        TERMINAL,

        /** No failure was ever recorded: the app died mid-dictation. */
        INTERRUPTED
    }

    fun of(completed: Boolean, failureClass: String?): Kind = when {
        completed -> Kind.COMPLETED
        failureClass == "AUTH" -> Kind.AUTH
        failureClass == "PROTOCOL" -> Kind.PROTOCOL
        failureClass == "TRANSIENT" -> Kind.NETWORK
        failureClass == "TERMINAL" -> Kind.TERMINAL
        else -> Kind.INTERRUPTED
    }

    /**
     * #185: what the Workbench retries on its own when it is entered. A dead
     * token, a provider protocol problem or a rejected request do not heal by
     * waiting — they stay for the manual list. Network failures and
     * interruptions do.
     */
    fun autoRetryable(kind: Kind): Boolean = kind == Kind.NETWORK || kind == Kind.INTERRUPTED
}
