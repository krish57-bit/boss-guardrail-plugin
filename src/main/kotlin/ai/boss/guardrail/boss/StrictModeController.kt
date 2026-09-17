package ai.boss.guardrail.boss

import ai.rever.boss.plugin.api.McpToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Strict mode: while the plugin is active, BOSS's own shell tools are switched
 * off through the supported MCP kill-switch ([McpToolRegistry.setToolEnabled]),
 * so an agent using the BOSS MCP server can only run shell commands through
 * `guardrail_run`.
 *
 * - Only tools that were enabled are switched off, and only those are switched
 *   back on in [release]. A tool the user had already disabled stays disabled.
 * - Each tool is handled once per activation. If the user turns one back on
 *   by hand, the plugin does not fight them.
 * - Tools from plugins that load later are caught by watching
 *   [McpToolRegistry.allTools].
 *
 * The kill-switch state is persisted by BOSS. If BOSS exits without disabling
 * the plugin, the tools stay off until the plugin next releases them or the
 * user turns them on in Toolbox → MCP.
 */
class StrictModeController(
    private val registry: McpToolRegistry,
    private val scope: CoroutineScope,
    private val guardedTools: Set<String> = SHELL_TOOLS,
) {
    private val handled = mutableSetOf<String>()
    private val disabledByUs = mutableSetOf<String>()
    private var watcher: Job? = null

    val toolsDisabledByGuardrail: Set<String>
        @Synchronized get() = disabledByUs.toSet()

    fun start() {
        sweep()
        watcher = scope.launch { registry.allTools.collect { sweep() } }
    }

    @Synchronized
    private fun sweep() {
        val present = registry.allTools.value.map { it.definition.name }.toSet()
        val alreadyOff = registry.disabledToolNames.value
        for (name in guardedTools) {
            if (name !in present || name in handled) continue
            handled += name
            if (name !in alreadyOff) {
                registry.setToolEnabled(name, false)
                disabledByUs += name
            }
        }
    }

    @Synchronized
    fun release() {
        watcher?.cancel()
        watcher = null
        for (name in disabledByUs) {
            runCatching { registry.setToolEnabled(name, true) }
        }
        disabledByUs.clear()
        handled.clear()
    }

    companion object {
        /** BOSS's shell-executing MCP tools (terminal-tab plugin), per BossConsole's McpRiskEvaluator. */
        val SHELL_TOOLS = setOf("run_command", "run_in_sidebar", "run_in_panel", "send_input", "terminal_exec")
    }
}
