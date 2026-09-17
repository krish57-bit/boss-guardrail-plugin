package ai.boss.guardrail.boss

import ai.boss.guardrail.config.GuardrailSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StrictModeTest {

    @Test
    @DisplayName("Strict mode switches off BOSS shell tools, respects the user's choices, and restores only what it changed")
    fun strictModeLifecycle() = runTest {
        val registry = FakeMcpRegistry().apply {
            addHostTool("run_command")
            addHostTool("send_input")
            addHostTool("git_status") // not a shell tool: untouched
            setToolEnabled("send_input", false) // the user had already turned this off
        }
        val host = FakeHost(scope = backgroundScope, registry = registry)
        val plugin = host.plugin(RecordingRunner(), GuardrailSettings(strictMode = true))
        runCurrent()

        val exposed = { registry.tools.value.map { it.definition.name }.toSet() }
        assertFalse("run_command" in exposed())
        assertTrue("git_status" in exposed())
        assertTrue("guardrail_run" in exposed())
        assertEquals(invoke(registry, "run_command").isError, true, "agent can no longer call run_command")

        // A shell tool from a plugin that loads later is caught too.
        registry.addHostTool("run_in_sidebar")
        runCurrent()
        assertFalse("run_in_sidebar" in exposed())

        // The user turns run_command back on by hand: the plugin does not fight it.
        registry.setToolEnabled("run_command", true)
        runCurrent()
        assertTrue("run_command" in exposed())
        assertEquals(setOf("run_command", "run_in_sidebar"), plugin.strictMode!!.toolsDisabledByGuardrail)
        assertTrue(plugin.statusNote().startsWith("Strict mode"))

        plugin.dispose()
        runCurrent()

        assertTrue("run_command" in exposed())
        assertTrue("run_in_sidebar" in exposed())
        assertFalse("send_input" in exposed(), "a tool the user disabled stays disabled")

        // After release, new shell tools are left alone.
        registry.addHostTool("run_in_panel")
        runCurrent()
        assertTrue("run_in_panel" in exposed())
    }

    @Test
    @DisplayName("Strict mode off: BOSS shell tools are left alone")
    fun strictModeOff() = runTest {
        val registry = FakeMcpRegistry().apply { addHostTool("run_command") }
        val host = FakeHost(scope = backgroundScope, registry = registry)
        val plugin = host.plugin(RecordingRunner())
        runCurrent()
        assertTrue(registry.tools.value.any { it.definition.name == "run_command" })
        assertTrue(plugin.statusNote().contains("Strict mode is off"))
    }

    private suspend fun invoke(registry: FakeMcpRegistry, name: String) = registry.invoke(name, "{}")
}
