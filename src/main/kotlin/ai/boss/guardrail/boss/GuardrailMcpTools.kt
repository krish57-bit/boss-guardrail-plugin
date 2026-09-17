package ai.boss.guardrail.boss

import ai.boss.guardrail.exec.CommandResult
import ai.boss.guardrail.exec.CommandRunner
import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.boss.guardrail.model.ExecutionOutcome
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.time.Duration.Companion.seconds

/**
 * The `guardrail_*` tools. They surface to agents as `mcp__boss__guardrail_*`
 * and exist only while the plugin is active.
 *
 * `guardrail_run` is the only way commands reach the interceptor. Commands an
 * agent runs any other way (BOSS's `run_command`, the agent's own shell tool,
 * other plugins' tools) are not seen by this plugin. See README.
 */
class GuardrailMcpToolProvider(
    override val providerId: String,
    private val interceptor: GuardrailInterceptor,
    private val runner: CommandRunner,
    private val defaultWorkingDir: () -> String?,
    private val maxCommandSeconds: Int,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(run(), check(), auditLog())

    private fun run() = McpToolDefinition(
        name = RUN_TOOL,
        description = "Run a shell command on the user's machine after a safety check. " +
            "Commands that match a risk rule (deleting files, force-pushing, piping downloads into a shell, " +
            "and similar) wait for the user to approve them in BOSS; denied or unanswered commands do not run. " +
            "Prefer this over other shell tools. Output is stdout and stderr combined. " +
            "Not for long-running servers: the command is stopped after timeout_seconds.",
        inputSchema = """
            {"type":"object","properties":{
              "command":{"type":"string","description":"Shell command (run with /bin/sh -c, or cmd.exe /c on Windows)"},
              "cwd":{"type":"string","description":"Working directory. Defaults to the open BOSS project."},
              "timeout_seconds":{"type":"integer","description":"Stop the command after this many seconds (default and max 55; BOSS ends every tool call at 60s, including approval time)"}
            },"required":["command"]}
        """.trimIndent(),
        readOnly = false,
        handler = McpToolHandler { args ->
            val command = args.string("command")?.takeIf { it.isNotBlank() }
                ?: return@McpToolHandler McpToolResult("command is required.", isError = true)
            val cwd = (args.string("cwd")?.takeIf { it.isNotBlank() } ?: defaultWorkingDir())?.let(::File)
            if (cwd != null && !cwd.isDirectory) {
                return@McpToolHandler McpToolResult("cwd does not exist or is not a directory: ${cwd.path}", isError = true)
            }
            val limit = (args.int("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1, maxCommandSeconds).seconds

            var result: CommandResult? = null
            val outcome = interceptor.executeGuarded(MCP_SESSION, command) {
                runner.run(it, cwd, limit).also { r -> result = r }.output
            }
            when (outcome) {
                is ExecutionOutcome.Success -> format(result!!)
                is ExecutionOutcome.Blocked -> McpToolResult(
                    "BLOCKED, the command did not run. ${outcome.reason}\n" +
                        "Do not retry the same command in another way; ask the user instead.",
                    isError = true,
                )
                is ExecutionOutcome.Failed -> McpToolResult("Could not start the command: ${outcome.error}", isError = true)
            }
        },
    )

    private fun check() = McpToolDefinition(
        name = "guardrail_check",
        description = "Check how the guardrail would treat a shell command, without running it.",
        inputSchema = """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}""",
        readOnly = true,
        handler = McpToolHandler { args ->
            val command = args.string("command")
                ?: return@McpToolHandler McpToolResult("command is required.", isError = true)
            val eval = interceptor.policyEngine.evaluate(command)
            val verdict = when {
                eval.isSafe -> "would run without asking"
                interceptor.isCommandAllowedInSession(MCP_SESSION, command) -> "would run (already allowed for this session)"
                else -> "would ask the user first"
            }
            McpToolResult(
                buildString {
                    appendLine("Risk: ${eval.riskLevel}. Verdict: $verdict.")
                    eval.triggeredRules.forEach { appendLine("- ${it.id}: ${it.explanation}") }
                }.trimEnd(),
            )
        },
    )

    private fun auditLog() = McpToolDefinition(
        name = "guardrail_audit_log",
        description = "Recent commands the guardrail handled and what happened to each.",
        inputSchema = """{"type":"object","properties":{"limit":{"type":"integer","description":"Max entries (default 20)"}}}""",
        readOnly = true,
        handler = McpToolHandler { args ->
            val limit = (args.int("limit") ?: 20).coerceIn(1, 200)
            val entries = interceptor.auditLogs.value.takeLast(limit)
            if (entries.isEmpty()) return@McpToolHandler McpToolResult("No commands yet.")
            val fmt = SimpleDateFormat("HH:mm:ss")
            McpToolResult(
                entries.joinToString("\n") { e ->
                    val what = when (val o = e.outcome) {
                        is ExecutionOutcome.Success -> "ran"
                        is ExecutionOutcome.Blocked -> "blocked"
                        is ExecutionOutcome.Failed -> "failed: ${o.error}"
                    }
                    "${fmt.format(Date(e.timestampMs))}  ${e.riskLevel}  ${e.decision ?: "-"}  $what  ${e.command.take(200)}"
                },
            )
        },
    )

    private fun format(r: CommandResult): McpToolResult {
        val header = when {
            r.timedOut -> "Stopped after the time limit."
            else -> "Exit code ${r.exitCode}."
        }
        val body = r.output.ifEmpty { "(no output)" }
        val tail = if (r.truncated) "\n[output truncated]" else ""
        return McpToolResult("$header\n$body$tail", isError = r.timedOut || r.exitCode != 0)
    }

    companion object {
        const val RUN_TOOL = "guardrail_run"
        /** All MCP callers share one session: the tool call carries no agent identity. */
        const val MCP_SESSION = "boss-mcp"
        const val DEFAULT_TIMEOUT_SECONDS = 55
    }
}
