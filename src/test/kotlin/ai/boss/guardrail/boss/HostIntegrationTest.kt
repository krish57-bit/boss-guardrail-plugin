package ai.boss.guardrail.boss

import ai.boss.guardrail.config.GuardrailSettings
import ai.rever.boss.plugin.api.DialogButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plugin as BOSS drives it: `register(context)` with a [ai.rever.boss.plugin.api.PluginContext],
 * then an agent's MCP call arriving as `McpToolRegistry.invoke("guardrail_run", "<json>")`.
 * Nothing here calls the interceptor directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostIntegrationTest {

    private val risky = """{"command":"rm -rf build/"}"""

    private fun TestScope.host(answer: suspend () -> DialogButton = { DialogButton.NEGATIVE }, projectPath: String? = null) =
        FakeHost(scope = backgroundScope, dialogs = FakeDialogs(answer), projectPath = projectPath)

    @Test
    @DisplayName("register() contributes the guardrail tools and the panel through the plugin API")
    fun registersToolsAndPanel() = runTest {
        val host = host()
        host.plugin(RecordingRunner())
        val names = host.registry.tools.value.map { it.definition.name }.toSet()
        assertEquals(setOf("guardrail_run", "guardrail_check", "guardrail_audit_log"), names)
        assertFalse(host.registry.definition("guardrail_run").readOnly, "guardrail_run changes state and must say so")
        assertTrue(host.registry.definition("guardrail_check").readOnly)
        assertTrue(host.registry.tools.value.all { it.providerId == GuardrailDynamicPlugin.PLUGIN_ID })
        assertEquals(listOf("agent-guardrail"), host.registeredPanels)
    }

    @Test
    @DisplayName("A safe command runs once with no dialog, in the open project")
    fun safeCommandRuns() = runTest {
        val dir = Files.createTempDirectory("proj").toFile()
        val host = host(projectPath = dir.path)
        val runner = RecordingRunner(output = "On branch main")
        host.plugin(runner)

        val result = host.registry.invoke("guardrail_run", """{"command":"git status"}""")

        assertFalse(result.isError, result.text)
        assertTrue(result.text.contains("On branch main"))
        assertEquals(listOf("git status"), runner.commands)
        assertEquals(dir, runner.workingDirs.single())
        assertEquals(0, host.dialogs!!.threeButtonCalls.get())
    }

    @Test
    @DisplayName("Deny: the command never runs")
    fun denyNeverRuns() = runTest {
        val host = host { DialogButton.NEGATIVE }
        val runner = RecordingRunner()
        host.plugin(runner)

        val result = host.registry.invoke("guardrail_run", risky)

        assertTrue(result.isError)
        assertTrue(result.text.startsWith("BLOCKED"), result.text)
        assertTrue(runner.commands.isEmpty())
        assertEquals(1, host.dialogs!!.threeButtonCalls.get())
        assertTrue(host.dialogs.messages.single().contains("rm -rf build/"))
    }

    @Test
    @DisplayName("Dismissed dialog counts as deny")
    fun dismissedNeverRuns() = runTest {
        val host = host { DialogButton.CANCELLED }
        val runner = RecordingRunner()
        host.plugin(runner)
        assertTrue(host.registry.invoke("guardrail_run", risky).isError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    @DisplayName("A dialog that throws counts as deny")
    fun brokenDialogNeverRuns() = runTest {
        val host = host { error("dialog host crashed") }
        val runner = RecordingRunner()
        host.plugin(runner)
        assertTrue(host.registry.invoke("guardrail_run", risky).isError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    @DisplayName("No dialog provider on this host: fail closed")
    fun noDialogProviderNeverRuns() = runTest {
        val host = FakeHost(scope = backgroundScope, dialogs = null)
        val runner = RecordingRunner()
        host.plugin(runner)
        val result = host.registry.invoke("guardrail_run", risky)
        assertTrue(result.isError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    @DisplayName("Timeout: the command never runs and the stale dialog is replaced")
    fun timeoutNeverRuns() = runTest {
        val host = host { awaitCancellation() }
        val runner = RecordingRunner()
        host.plugin(runner, GuardrailSettings(approvalTimeoutSeconds = 10))

        val call = async { host.registry.invoke("guardrail_run", risky) }
        advanceTimeBy(9_000)
        runCurrent()
        assertFalse(call.isCompleted, "still waiting before the timeout")
        advanceTimeBy(2_000)
        runCurrent()
        val result = call.await()

        assertTrue(result.isError)
        assertTrue(result.text.contains("timed out"), result.text)
        assertTrue(runner.commands.isEmpty())
        runCurrent()
        assertEquals(listOf("Agent Guardrail: command not run"), host.dialogs!!.alerts)
    }

    @Test
    @DisplayName("BOSS's 60s tool timeout ends the wait without running the command")
    fun hostTimeoutNeverRuns() = runTest {
        val registry = FakeMcpRegistry(hostTimeoutMs = 3_000)
        val host = FakeHost(scope = backgroundScope, dialogs = FakeDialogs { awaitCancellation() }, registry = registry)
        val runner = RecordingRunner()
        host.plugin(runner, GuardrailSettings(approvalTimeoutSeconds = 45))

        val result = registry.invoke("guardrail_run", risky)

        assertTrue(result.isError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    @DisplayName("Caller cancellation while the dialog is open: the command never runs, even if Allow is clicked later")
    fun cancellationNeverRuns() = runTest {
        val click = CompletableDeferred<DialogButton>()
        val host = host { click.await() }
        val runner = RecordingRunner()
        host.plugin(runner)

        val call = launch { host.registry.invoke("guardrail_run", risky) }
        runCurrent()
        assertEquals(1, host.dialogs!!.threeButtonCalls.get())
        call.cancelAndJoin()
        click.complete(DialogButton.POSITIVE) // user clicks after the agent gave up
        runCurrent()

        assertTrue(runner.commands.isEmpty())
        assertEquals(listOf("Agent Guardrail: command not run"), host.dialogs.alerts)
    }

    @Test
    @DisplayName("Allow once runs exactly once and is not remembered")
    fun allowOnceRunsExactlyOnce() = runTest {
        val host = host { DialogButton.POSITIVE }
        val runner = RecordingRunner()
        host.plugin(runner)

        val first = host.registry.invoke("guardrail_run", risky)
        assertFalse(first.isError, first.text)
        assertEquals(1, runner.commands.size)
        assertEquals(1, host.dialogs!!.threeButtonCalls.get())

        host.dialogs.answer = { DialogButton.NEGATIVE }
        val second = host.registry.invoke("guardrail_run", risky)
        assertTrue(second.isError, "second call must ask again, and was denied")
        assertEquals(1, runner.commands.size)
        assertEquals(2, host.dialogs.threeButtonCalls.get())
    }

    @Test
    @DisplayName("Allow for session: later identical commands skip the dialog, others still ask")
    fun allowForSession() = runTest {
        val host = host { DialogButton.NEUTRAL }
        val runner = RecordingRunner()
        host.plugin(runner)

        host.registry.invoke("guardrail_run", risky)
        host.registry.invoke("guardrail_run", """{"command":"rm   -rf  build/"}""")
        assertEquals(2, runner.commands.size)
        assertEquals(1, host.dialogs!!.threeButtonCalls.get())

        host.dialogs.answer = { DialogButton.NEGATIVE }
        host.registry.invoke("guardrail_run", """{"command":"rm -rf src/"}""")
        assertEquals(2, runner.commands.size)
        assertEquals(2, host.dialogs.threeButtonCalls.get())
    }

    @Test
    @DisplayName("Concurrent risky calls get one dialog each, never merged")
    fun concurrentCallsEachAsk() = runTest {
        val host = host { DialogButton.POSITIVE }
        val runner = RecordingRunner()
        host.plugin(runner)

        val a = async { host.registry.invoke("guardrail_run", """{"command":"git reset --hard"}""") }
        val b = async { host.registry.invoke("guardrail_run", """{"command":"git clean -fdx"}""") }
        a.await(); b.await()

        assertEquals(2, host.dialogs!!.threeButtonCalls.get())
        assertEquals(setOf("git reset --hard", "git clean -fdx"), runner.commands.toSet())
    }

    @Test
    @DisplayName("Bad arguments are rejected before anything runs")
    fun badArguments() = runTest {
        val host = host { DialogButton.POSITIVE }
        val runner = RecordingRunner()
        host.plugin(runner)
        assertTrue(host.registry.invoke("guardrail_run", "{}").isError)
        assertTrue(host.registry.invoke("guardrail_run", "not json").isError)
        assertTrue(host.registry.invoke("guardrail_run", """{"command":"ls","cwd":"/definitely/not/here"}""").isError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    @DisplayName("requireApprovalForAll asks even for commands no rule matches")
    fun approvalForAll() = runTest {
        val host = host { DialogButton.NEGATIVE }
        val runner = RecordingRunner()
        host.plugin(runner, GuardrailSettings(requireApprovalForAll = true))
        assertTrue(host.registry.invoke("guardrail_run", """{"command":"ls"}""").isError)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    @DisplayName("guardrail_check never runs anything or opens a dialog")
    fun checkIsDryRun() = runTest {
        val host = host { DialogButton.POSITIVE }
        val runner = RecordingRunner()
        host.plugin(runner)
        val result = host.registry.invoke("guardrail_check", risky)
        assertTrue(result.text.contains("would ask"), result.text)
        assertTrue(result.text.contains("FS_RECURSIVE_DELETE"))
        assertTrue(runner.commands.isEmpty())
        assertEquals(0, host.dialogs!!.threeButtonCalls.get())
    }

    @Test
    @DisplayName("Audit log reports what happened")
    fun auditLog() = runTest {
        val host = host { DialogButton.NEGATIVE }
        host.plugin(RecordingRunner())
        host.registry.invoke("guardrail_run", """{"command":"git status"}""")
        host.registry.invoke("guardrail_run", risky)
        val log = host.registry.invoke("guardrail_audit_log", "{}").text
        assertTrue(log.contains("ran") && log.contains("git status"), log)
        assertTrue(log.contains("blocked") && log.contains("rm -rf build/"), log)
    }

    @Test
    @DisplayName("dispose() forgets session approvals and stops accepting commands")
    fun disposeClearsState() = runTest {
        val host = host { DialogButton.NEUTRAL }
        val runner = RecordingRunner()
        val plugin = host.plugin(runner)
        host.registry.invoke("guardrail_run", risky)
        val interceptor = plugin.interceptor!!

        plugin.dispose()

        assertNull(plugin.interceptor)
        assertFalse(interceptor.isCommandAllowedInSession(GuardrailMcpToolProvider.MCP_SESSION, "rm -rf build/"))
        assertFalse(interceptor.isEnabled.value)
    }
}
