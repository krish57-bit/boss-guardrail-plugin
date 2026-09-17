package ai.boss.guardrail.boss

import ai.boss.guardrail.config.GuardrailSettings
import ai.boss.guardrail.exec.CommandResult
import ai.boss.guardrail.exec.CommandRunner
import ai.rever.boss.plugin.api.DialogButton
import ai.rever.boss.plugin.api.DialogChoice
import ai.rever.boss.plugin.api.DialogChoiceItem
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.ProgressDialogHandle
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Stand-in for the BOSS host, built only from the public plugin API.
 *
 * [FakeMcpRegistry.invoke] follows BossConsole's `McpToolRegistryCore.invoke`
 * for the parts that matter here: look the tool up among exposed (not
 * disabled) tools, parse the JSON arguments into [McpToolArgs] the same way
 * `parseArgs` does, and call the handler under a 60 s `withTimeout`.
 */
class FakeMcpRegistry(private val hostTimeoutMs: Long = 60_000) : McpToolRegistry {
    private val providers = linkedMapOf<String, McpToolProvider>()
    private val extra = mutableListOf<RegisteredMcpTool>()
    private val _all = MutableStateFlow<List<RegisteredMcpTool>>(emptyList())
    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    private val _tools = MutableStateFlow<List<RegisteredMcpTool>>(emptyList())
    override val allTools: StateFlow<List<RegisteredMcpTool>> = _all
    override val disabledToolNames: StateFlow<Set<String>> = _disabled
    override val tools: StateFlow<List<RegisteredMcpTool>> = _tools

    fun register(provider: McpToolProvider) {
        providers[provider.providerId] = provider
        recompute()
    }

    fun unregister(providerId: String) {
        providers.remove(providerId)
        recompute()
    }

    /** A tool from another plugin, e.g. terminal-tab's run_command. */
    fun addHostTool(name: String) {
        extra += RegisteredMcpTool("terminal-tab", McpToolDefinition(name, name, handler = McpToolHandler { McpToolResult("ran $name") }))
        recompute()
    }

    override fun setToolEnabled(toolName: String, enabled: Boolean) {
        _disabled.value = if (enabled) _disabled.value - toolName else _disabled.value + toolName
        recompute()
    }

    private fun recompute() {
        _all.value = extra + providers.values.flatMap { p -> p.tools().map { RegisteredMcpTool(p.providerId, it) } }
        _tools.value = _all.value.filter { it.definition.name !in _disabled.value }
    }

    fun definition(name: String): McpToolDefinition = _all.value.first { it.definition.name == name }.definition

    override suspend fun invoke(toolName: String, arguments: String): McpToolResult {
        val tool = _tools.value.firstOrNull { it.definition.name == toolName }
            ?: return McpToolResult("Unknown or disabled MCP tool: $toolName", isError = true)
        val args = parseArgs(arguments)
        return try {
            withTimeout(hostTimeoutMs) { tool.definition.handler.call(args) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            McpToolResult("MCP tool timed out", isError = true)
        }
    }

    private fun parseArgs(arguments: String): McpToolArgs {
        val map: Map<String, Any?> = try {
            (Json.parseToJsonElement(arguments) as? JsonObject)?.mapValues { (_, el) ->
                when {
                    el is JsonNull -> null
                    el is JsonPrimitive -> if (el.isString) el.content else el.booleanOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.content
                    else -> el.toString()
                }
            } ?: emptyMap()
        } catch (t: Throwable) {
            emptyMap()
        }
        return McpToolArgs(map, arguments.ifBlank { "{}" })
    }
}

/** Scripted host dialog. [answer] decides each three-button dialog. */
class FakeDialogs(var answer: suspend () -> DialogButton) : GenericDialogProvider {
    val threeButtonCalls = AtomicInteger()
    val alerts = mutableListOf<String>()
    val messages = mutableListOf<String>()

    override suspend fun showThreeButtonDialog(
        title: String, message: String, positiveText: String, negativeText: String, neutralText: String,
    ): DialogButton {
        threeButtonCalls.incrementAndGet()
        messages += message
        return answer()
    }

    override suspend fun showAlertDialog(title: String, message: String, buttonText: String) {
        synchronized(alerts) { alerts += title }
    }

    override suspend fun showTextInputDialog(
        title: String, message: String?, initialValue: String, placeholder: String, validation: ((String) -> String?)?,
    ): String? = error("unused")

    override suspend fun showConfirmationDialog(
        title: String, message: String, confirmText: String, cancelText: String, isDestructive: Boolean,
    ): Boolean = error("unused")

    override suspend fun showChoiceDialog(
        title: String, message: String?, choices: List<DialogChoice>, selectedIndex: Int,
    ): DialogChoice? = error("unused")

    override suspend fun showMultiChoiceDialog(
        title: String, message: String?, choices: List<DialogChoiceItem>,
    ): List<DialogChoiceItem>? = error("unused")

    override fun showProgressDialog(
        title: String, message: String, isIndeterminate: Boolean, cancellable: Boolean,
    ): ProgressDialogHandle = error("unused")
}

/** Records what it was asked to run instead of running it. */
class RecordingRunner(private val output: String = "ok", private val exitCode: Int = 0) : CommandRunner {
    val commands = mutableListOf<String>()
    val workingDirs = mutableListOf<File?>()
    override suspend fun run(command: String, workingDir: File?, timeout: Duration): CommandResult {
        synchronized(commands) {
            commands += command
            workingDirs += workingDir
        }
        return CommandResult(exitCode, output)
    }
}

class FakeHost(
    val scope: CoroutineScope,
    val dialogs: FakeDialogs? = FakeDialogs { DialogButton.NEGATIVE },
    val registry: FakeMcpRegistry = FakeMcpRegistry(),
    val projectPath: String? = null,
) {
    val registeredPanels = mutableListOf<String>()

    private val panelRegistry = object : PanelRegistry() {
        override fun registerPanel(
            content: ai.rever.boss.plugin.api.PanelInfo,
            factory: (com.arkivanov.decompose.ComponentContext, ai.rever.boss.plugin.api.PanelInfo) -> ai.rever.boss.plugin.api.PanelComponentWithUI,
        ) {
            registeredPanels += content.id.panelId
        }
    }

    val context: PluginContext = Proxy.newProxyInstance(
        PluginContext::class.java.classLoader, arrayOf(PluginContext::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "FakePluginContext"
            "getPluginScope" -> scope
            "getGenericDialogProvider" -> dialogs
            "getMcpToolRegistry" -> registry
            "getPanelRegistry" -> panelRegistry
            "getProjectPath" -> projectPath
            "registerMcpToolProvider" -> { registry.register(args!![0] as McpToolProvider); null }
            "unregisterMcpToolProvider" -> { registry.unregister(args!![0] as String); null }
            else -> defaultFor(method.returnType)
        }
    } as PluginContext

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }

    fun plugin(
        runner: CommandRunner,
        settings: GuardrailSettings = GuardrailSettings(),
    ): GuardrailDynamicPlugin =
        GuardrailDynamicPlugin(runner, { settings }, { emptyList() }).also { it.register(context) }
}
