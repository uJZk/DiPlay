package com.shilapi.xcertplay.network

import android.os.Looper
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs one fixed root script through `su` (Magisk, KernelSU …) and reports how it ended.
 *
 * The script goes to `su`'s stdin instead of `su -c`, whose meaning differs between implementations. It may hold only
 * the characters of the validated tokens TiPlay builds (see [HotspotExtraAddress]), so no shell syntax can ever reach the
 * root shell. Blocking, bounded by a timeout, never on the main thread.
 */
object RootShell {
    sealed interface Result {
        /** The script ran as far as the exit marker; [output] is everything it printed before the marker. */
        data class Done(val exitCode: Int, val output: String) : Result
        /** No `su` binary, root refused, or `su` ended without running the script. */
        data object Unavailable : Result
        data object TimedOut : Result
    }

    const val EXIT_MARKER = "TIPLAY_ROOT_EXIT:"
    const val DEFAULT_TIMEOUT_MILLIS = 8_000L
    /** The first call may wait for the root manager's grant dialog. */
    const val PROMPT_TIMEOUT_MILLIS = 30_000L
    private const val MAX_TIMEOUT_MILLIS = 60_000L
    private const val MAX_OUTPUT_CHARS = 16_384
    private const val READER_JOIN_MILLIS = 1_000L

    // Letters, digits, space and `_ . / -`: enough for `id -u` and `ip -4 addr replace A.B.C.D/32 dev IFACE`.
    private val SAFE_SCRIPT = Regex("[A-Za-z0-9_./ -]{1,256}")
    private val MARKER_LINE = Regex("(?m)^" + Regex.escape(EXIT_MARKER) + "([0-9]{1,3})[ \\t\\r]*$")

    /** Runs [script] as root. Throws [IllegalArgumentException] for a script outside the safe character set. */
    fun run(script: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Result {
        check(!onMainThread()) { "RootShell.run must not run on the main thread" }
        return execute(listOf("su"), script, timeoutMillis)
    }

    internal fun execute(command: List<String>, script: String, timeoutMillis: Long): Result {
        require(SAFE_SCRIPT.matches(script)) { "root script holds characters outside the safe set" }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS" }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return Result.Unavailable
        } catch (_: SecurityException) {
            return Result.Unavailable
        }
        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(1_024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            output.append(buffer, 0, count)
                            // The marker comes last, so keep the tail.
                            if (output.length > MAX_OUTPUT_CHARS) output.delete(0, output.length - MAX_OUTPUT_CHARS)
                        }
                    }
                }
            }
        }, "tiplay-root-output").apply { isDaemon = true; start() }
        // A refusing su may exit before reading stdin; the missing marker reports that below.
        runCatching { process.outputStream.bufferedWriter().use { it.write(wrap(script)) } }
        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            reader.interrupt()
            return Result.TimedOut
        }
        try {
            reader.join(READER_JOIN_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { process.inputStream.close() }
        return parse(synchronized(output) { output.toString() })
    }

    /** The text written to `su`: the script, then its exit status behind [EXIT_MARKER]. */
    internal fun wrap(script: String): String =
        "$script\nstatus=\$?\nprintf '\\n%s%s\\n' '$EXIT_MARKER' \"\$status\"\nexit\n"

    /** Only the last marker line counts; without one, `su` never ran the script. */
    internal fun parse(output: String): Result {
        val marker = MARKER_LINE.findAll(output).lastOrNull() ?: return Result.Unavailable
        val code = marker.groupValues[1].toIntOrNull() ?: return Result.Unavailable
        return Result.Done(code, output.substring(0, marker.range.first).trim())
    }

    private fun onMainThread(): Boolean =
        runCatching { Looper.getMainLooper()?.isCurrentThread == true }.getOrDefault(false)
}
