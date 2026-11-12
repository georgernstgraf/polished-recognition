package com.georgernstgraf.polishedrecognition.analyzer.corpus

import com.georgernstgraf.polishedrecognition.analyzer.Args
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `pull` — the adb encapsulation (owner decision 4, binding). Pulls the
 * retained forensic bundles (`files/audio/<sessionId>/` = `uploads/` +
 * `session.json`) off a device into a corpus's `bundles/` directory.
 *
 * The adb gotchas it encapsulates (all PITFALLS entries, hard-won):
 * - `adb pull` is BLOCKED on the owner's devices (Oplus scoped storage) —
 *   files come over via `adb exec-out cat` per file (exec-out is
 *   binary-safe; `adb shell`'s pty would mangle WAV bytes with CRLF).
 * - Directory listings go through `adb shell ls`, and their `\r` must be
 *   STRIPPED.
 * - Every adb call inside a loop redirects stdin from /dev/null — adb shell
 *   EATS the loop's stdin otherwise.
 * - The serial is PINNED (`-s <serial>`; two devices are usually attached —
 *   `f6de166c` = OnePlus 7T, `R7SRB06XF5K` = Samsung A03).
 * - Destination: the corpus directory (bundles are corpus material).
 */
object PullCommand {

    const val DEVICE_AUDIO_ROOT =
        "/sdcard/Android/data/com.georgernstgraf.polishedrecognition/files/audio"

    fun run(args: Args) {
        val corpusName = args.requireOne("corpus")
        val corpus = Corpus.locate(corpusName)
        val serial = args.optOr("serial", System.getenv("ANDROID_SERIAL") ?: "")
        val dest = corpus.bundlesDir()

        fun adb(vararg cmd: String): ProcessBuilder {
            val full = mutableListOf("adb")
            if (serial.isNotEmpty()) full.addAll(listOf("-s", serial))
            full.addAll(cmd)
            return ProcessBuilder(full)
                .redirectInput(File("/dev/null"))
                .apply { redirectErrorStream(true) }
        }

        if (serial.isNotEmpty()) {
            // fail fast on an unpinned/mis-spelled serial (misleading errors otherwise)
            val devices = run(adb("devices")).toString(Charsets.UTF_8)
            if (!devices.lines().any { it.trim().startsWith(serial) && it.contains("device") }) {
                throw CorpusException("serial '$serial' not attached:\n$devices")
            }
        }

        println("listing $DEVICE_AUDIO_ROOT/ ...")
        val sessions = run(adb("shell", "ls", "-1", DEVICE_AUDIO_ROOT))
            .toString(Charsets.UTF_8)
            .split("\n")
            .map { it.replace("\r", "").trim() }
            .filter { it.isNotEmpty() && !it.contains("No such file") && !it.contains("Permission denied") }
        if (sessions.isEmpty()) {
            println("no retained sessions on the device (retention 0/off, or wrong device?)")
            return
        }
        println("${sessions.size} session dirs; pulling each into ${dest.path}")

        var pulled = 0
        var legacy = 0
        for (sessionId in sessions) {
            val sessionDir = File(dest, sessionId)
            val deviceDir = "$DEVICE_AUDIO_ROOT/$sessionId"
            try {
                val entries = listOrEmpty(adb("shell", "ls", "-1p", deviceDir))
                if (entries.isEmpty()) throw CorpusException("unlistable (vanished or access denied)")
                // A bundle is `uploads/` + session.json (#142). Pre-#142 sessions
                // (session.wav + fragments/) carry no forensic record and cannot be
                // scanned — pulling them only pollutes the corpus with bundles that
                // every scan has to skip (and, before the tolerant loader, aborted it).
                if (!isBundleListing(entries)) {
                    legacy++
                    println("  $sessionId: SKIPPED (pre-#142 session — no session.json)")
                    continue
                }
                pullEntries({ cmd -> adb(*cmd) }, deviceDir, sessionDir, entries, depth = 1)
            } catch (e: Exception) {
                println("  $sessionId: SKIPPED (${e.message?.lineSequence()?.first() ?: "error"})")
                continue
            }
            pulled++
            println("  $sessionId")
        }
        println("pulled $pulled bundles -> ${dest.path}" + if (legacy > 0) " ($legacy pre-#142 sessions skipped)" else "")
    }

    /**
     * A device session dir is a BUNDLE only when it carries `session.json`
     * (#142). Pre-#142 dirs (`session.wav` + `fragments/`) hold no forensic
     * record, so they are skipped instead of being pulled into the corpus.
     */
    internal fun isBundleListing(entries: List<String>): Boolean =
        entries.any { !it.endsWith("/") && it == "session.json" }

    /**
     * Pulls one device directory file-by-file, recursing one level. Directories
     * are recognized via `ls -1p`'s trailing "/" (toybox marks them) — the
     * bundle layouts are flat (`uploads/`), so a single level covers the #142
     * format (uploads/ + session.json).
     */
    private fun pullEntries(
        adbCmd: (Array<out String>) -> ProcessBuilder,
        devicePath: String,
        targetDir: File,
        entries: List<String>,
        depth: Int
    ) {
        targetDir.mkdirs()
        for (entry in entries) {
            if (entry.endsWith("/")) {
                val name = entry.trimEnd('/')
                if (depth > 0) {
                    val sub = listOrEmpty(adbCmd(arrayOf("shell", "ls", "-1p", "$devicePath/$name")))
                    pullEntries(adbCmd, "$devicePath/$name", File(targetDir, name), sub, depth - 1)
                }
                continue
            }
            val content = run(adbCmd(arrayOf("exec-out", "cat", "$devicePath/$entry")))
            File(targetDir, entry).writeBytes(content)
        }
    }

    /**
     * Runs an adb command and returns its merged stdout/stderr. The output is
     * DRAINED on a background thread BEFORE `waitFor` — reading after a
     * `waitFor` deadlocks on any output larger than the OS pipe buffer
     * (~64 KB): the child blocks writing, never exits, and the wait burns its
     * whole timeout (the 2026-10-15 A03 pull crashed exactly there on the
     * 356-KB session.wav while the small `ls` listings sailed through).
     */
    internal fun run(pb: ProcessBuilder, timeoutSec: Long = 60): ByteArray {
        val process = pb.start()
        val out = java.io.ByteArrayOutputStream()
        val reader = kotlin.concurrent.thread(isDaemon = true) {
            process.inputStream.copyTo(out)
        }
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            throw CorpusException("adb command timed out: ${pb.command()}")
        }
        reader.join(5000)
        if (process.exitValue() != 0) {
            throw CorpusException("adb command failed (${process.exitValue()}): ${pb.command()}")
        }
        return out.toByteArray()
    }

    /** Best-effort listing: returns empty on adb failure (a vanished session, e.g.). */
    private fun listOrEmpty(pb: ProcessBuilder): List<String> = try {
        run(pb).toString(Charsets.UTF_8)
            .split("\n")
            .map { it.replace("\r", "").trim() }
            .filter { it.isNotEmpty() && !it.contains("No such file") && !it.contains("Permission denied") }
    } catch (e: Exception) {
        emptyList()
    }
}
