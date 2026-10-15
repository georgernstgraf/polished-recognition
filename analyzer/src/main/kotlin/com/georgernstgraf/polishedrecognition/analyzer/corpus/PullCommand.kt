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
        for (sessionId in sessions) {
            val files = listOrEmpty(adb("shell", "ls", "-1", "$DEVICE_AUDIO_ROOT/$sessionId"))
            if (files.isEmpty()) {
                println("  $sessionId: SKIPPED (unlistable — vanished or access denied)")
                continue
            }
            val sessionDir = File(dest, sessionId)
            sessionDir.mkdirs()
            for (name in files) {
                val target = File(sessionDir, name)
                val content = run(adb("exec-out", "cat", "$DEVICE_AUDIO_ROOT/$sessionId/$name"))
                target.writeBytes(content)
            }
            pulled++
            println("  $sessionId (${files.size} files)")
        }
        println("pulled $pulled sessions -> ${dest.path}")
    }

    private fun run(pb: ProcessBuilder): ByteArray {
        val process = pb.start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw CorpusException("adb command timed out: ${pb.command()}")
        }
        if (process.exitValue() != 0) {
            throw CorpusException("adb command failed (${process.exitValue()}): ${pb.command()}")
        }
        return process.inputStream.readBytes()
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
