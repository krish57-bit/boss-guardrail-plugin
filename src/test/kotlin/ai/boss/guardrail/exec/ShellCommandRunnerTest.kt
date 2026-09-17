package ai.boss.guardrail.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Real processes, POSIX shell only. */
class ShellCommandRunnerTest {

    private val runner = ShellCommandRunner(maxOutputChars = 1_000)

    @BeforeEach
    fun posixOnly() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
    }

    @Test
    fun `captures output and exit code in the working directory`() = runBlocking {
        val dir = Files.createTempDirectory("guardrail").toFile()
        val result = runner.run("pwd; echo oops >&2; exit 3", dir, 10.seconds)
        assertEquals(3, result.exitCode)
        assertTrue(result.output.contains(dir.canonicalPath), result.output)
        assertTrue(result.output.contains("oops"))
        assertFalse(result.timedOut)
    }

    @Test
    fun `kills commands that run past the timeout`() = runBlocking {
        val started = System.nanoTime()
        val result = runner.run("sleep 30", null, 300.milliseconds)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertTrue((System.nanoTime() - started) < 10_000_000_000, "returned promptly")
    }

    @Test
    fun `cancelling the caller kills the process`() = runBlocking {
        val marker = Files.createTempFile("guardrail", ".pid").toFile()
        val job = launch(Dispatchers.Default) {
            runner.run("echo \$\$ > ${marker.path}; sleep 30", null, 60.seconds)
        }
        withContext(Dispatchers.IO) {
            repeat(100) { if (marker.readText().isNotBlank()) return@withContext; delay(20) }
        }
        val pid = marker.readText().trim().toLong()
        job.cancelAndJoin()
        val handle = ProcessHandle.of(pid)
        repeat(50) { if (!handle.isPresent || !handle.get().isAlive) return@runBlocking; delay(20) }
        error("process $pid still alive after cancellation")
    }

    @Test
    fun `truncates large output but still finishes`() = runBlocking {
        val result = runner.run("yes x | head -c 200000", null, 10.seconds)
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(1_000, result.output.length)
    }

    @Test
    fun `background children do not hang the call`() = runBlocking {
        val result = runner.run("sleep 30 & sleep 0.3; echo done", null, 10.seconds)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("done"))
    }
}
