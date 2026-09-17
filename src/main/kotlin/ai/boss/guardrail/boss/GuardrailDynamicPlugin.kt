package ai.boss.guardrail.boss

import ai.boss.guardrail.config.ConfigLoader
import ai.boss.guardrail.config.GuardrailSettings
import ai.boss.guardrail.engine.ShellPolicyEngine
import ai.boss.guardrail.exec.CommandRunner
import ai.boss.guardrail.exec.ShellCommandRunner
import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * BOSS entry point (manifest `mainClass`).
 *
 * On [register] it:
 * 1. registers the `guardrail_run`, `guardrail_check` and `guardrail_audit_log`
 *    MCP tools ([PluginContext.registerMcpToolProvider]);
 * 2. routes approvals to the host dialog ([PluginContext.genericDialogProvider]);
 * 3. registers the "Agent Guardrail" panel;
 * 4. in strict mode, switches off BOSS's own shell tools via
 *    [PluginContext.mcpToolRegistry] until [dispose].
 *
 * BOSS unregisters the MCP provider and panel itself on disable/unload.
 */
class GuardrailDynamicPlugin internal constructor(
    private val runner: CommandRunner,
    private val settingsLoader: () -> GuardrailSettings,
    private val rulesLoader: () -> List<ai.boss.guardrail.model.PolicyRule>,
) : DynamicPlugin {

    /** Used by the BOSS plugin loader (`getDeclaredConstructor().newInstance()`). */
    constructor() : this(ShellCommandRunner(), { ConfigLoader.loadSettings() }, { ConfigLoader.loadCustomRules() })

    override val pluginId = PLUGIN_ID
    override val displayName = "Agent Guardrail"
    override val version = VERSION
    override val description =
        "Adds a guardrail_run MCP tool that checks shell commands against risk rules and asks you in BOSS before running risky ones."
    override val author = "Krish Kaul"
    override val url = "https://github.com/krish57-bit/boss-guardrail-plugin"

    internal var interceptor: GuardrailInterceptor? = null
        private set
    internal var strictMode: StrictModeController? = null
        private set

    override fun register(context: PluginContext) {
        val settings = settingsLoader()
        val approvalTimeout = settings.approvalTimeoutSeconds.coerceIn(5, 55).seconds
        val prompter = HostDialogPrompter(
            dialogProvider = { context.genericDialogProvider },
            timeout = approvalTimeout,
            scope = context.pluginScope,
        )
        val interceptor = GuardrailInterceptor(
            policyEngine = ShellPolicyEngine(rulesLoader(), settings.requireApprovalForAll),
            defaultTimeout = approvalTimeout,
            prompter = prompter,
        ).also { this.interceptor = it }

        context.registerMcpToolProvider(
            GuardrailMcpToolProvider(
                providerId = pluginId,
                interceptor = interceptor,
                runner = runner,
                defaultWorkingDir = { context.projectPath?.takeIf { File(it).isDirectory } },
                maxCommandSeconds = settings.maxCommandSeconds.coerceIn(1, 55),
            ),
        )

        val registry = context.mcpToolRegistry
        if (settings.strictMode) {
            if (registry != null) {
                strictMode = StrictModeController(registry, context.pluginScope).also { it.start() }
            } else {
                System.err.println("[BOSS Guardrail] strictMode is on but this BOSS has no MCP tool registry; BOSS shell tools stay enabled.")
            }
        }

        context.panelRegistry.registerPanel(GuardrailPanelInfo) { ctx, panelInfo ->
            GuardrailPanelComponent(ctx, panelInfo, interceptor, ::statusNote)
        }
    }

    internal fun statusNote(): String {
        val strict = strictMode
        return when {
            strict == null -> "Covers commands agents send through mcp__boss__guardrail_run only. Strict mode is off."
            else -> {
                val off = strict.toolsDisabledByGuardrail
                "Strict mode: BOSS shell tools switched off while active: " +
                    (if (off.isEmpty()) "none yet" else off.sorted().joinToString())
            }
        }
    }

    override fun dispose() {
        strictMode?.release()
        strictMode = null
        interceptor?.setEnabled(false)
        interceptor?.clearSessionAllowList()
        interceptor = null
    }

    companion object {
        const val PLUGIN_ID = "io.github.krish57bit.guardrail"
        const val VERSION = "1.1.0"
    }
}
