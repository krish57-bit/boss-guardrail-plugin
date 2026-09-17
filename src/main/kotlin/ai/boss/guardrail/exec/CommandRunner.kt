package ai.boss.guardrail.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/** Runs an already-approved command. */
fun interface CommandRunner {
    suspend fun run(command: String, workingDir: File?, timeout: Duration): CommandResult
}

/**
 * Runs the command with the platform shell (`/bin/sh -c`, or `cmd.exe /c` on
 * Windows), with stdout and stderr merged.
 *
 * Cancelling the caller or hitting [timeout] kills the process tree, so an
 * abandoned call never leaves a command running in the background.
 */
class ShellCommandRunner(
    private val maxOutputChars: Int = 60_000,
) : CommandRunner {

    override suspend fun run(command: String, workingDir: File?, timeout: Duration): CommandResult =
        withContext(Dispatchers.IO) {
            val argv = if (isWindows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)
            val process = ProcessBuilder(argv)
                .directory(workingDir?.takeIf { it.isDirectory })
                .redirectErrorStream(true)
                .start()

            runCatching { process.outputStream.close() } // no stdin: interactive prompts fail instead of hanging
            coroutineScope {
                // Blocking read on its own thread; it ends once the process exits or is killed.
                val reader = async { readCapped(process) }
                val seen = mutableSetOf<ProcessHandle>()
                try {
                    val finished = withTimeoutOrNull(timeout) {
                        while (process.isAlive) {
                            runCatching { process.descendants().forEach { seen += it } }
                            delay(25)
                        }
                        true
                    } ?: false
                    if (!finished) killTree(process)
                    // Background children (`cmd &`) would keep the output pipe open forever.
                    // This tool is for commands that finish, so anything left over is stopped.
                    seen.filter { it.isAlive }.forEach { it.destroyForcibly() }
                    val (text, truncated) = reader.await()
                    CommandResult(
                        exitCode = if (finished) process.exitValue() else null,
                        output = text,
                        timedOut = !finished,
                        truncated = truncated,
                    )
                } finally {
                    // Runs on cancellation too, before this scope waits for the reader,
                    // which is what lets the reader finish.
                    if (process.isAlive) killTree(process)
                    seen.filter { it.isAlive }.forEach { it.destroyForcibly() }
                }
            }
        }

    private fun readCapped(process: Process): Pair<String, Boolean> {
        val sb = StringBuilder()
        var truncated = false
        val buf = CharArray(4096)
        runCatching {
            process.inputStream.bufferedReader().use { r ->
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    val room = maxOutputChars - sb.length
                    if (room > 0) sb.appendRange(buf, 0, minOf(n, room))
                    if (n > room) truncated = true // keep draining so the child never blocks on a full pipe
                }
            }
        }
        return sb.toString() to truncated
    }

    private fun killTree(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private companion object {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
