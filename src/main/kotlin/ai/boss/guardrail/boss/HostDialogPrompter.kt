package ai.boss.guardrail.boss

import ai.boss.guardrail.interceptor.ActiveApprovalRequest
import ai.boss.guardrail.interceptor.ApprovalPrompter
import ai.boss.guardrail.model.ApprovalDecision
import ai.rever.boss.plugin.api.DialogButton
import ai.rever.boss.plugin.api.GenericDialogProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Shows the approval as a BOSS host dialog through
 * [GenericDialogProvider.showThreeButtonDialog], so it looks like every other
 * BOSS prompt and needs no window of its own.
 *
 * Fails closed: no dialog provider, a dismissed dialog or an error all return
 * [ApprovalDecision.DENY].
 */
class HostDialogPrompter(
    private val dialogProvider: () -> GenericDialogProvider?,
    private val timeout: Duration,
    private val scope: CoroutineScope,
) : ApprovalPrompter {

    override suspend fun ask(request: ActiveApprovalRequest): ApprovalDecision {
        val dialogs = dialogProvider() ?: return ApprovalDecision.DENY
        val button = try {
            dialogs.showThreeButtonDialog(
                title = "Agent Guardrail: approve command?",
                message = message(request),
                positiveText = "Allow once",
                negativeText = "Deny",
                neutralText = "Allow for session",
            )
        } catch (e: CancellationException) {
            // The host dialog is not tied to our coroutine, so it would stay on screen
            // and a late "Allow" would look like it worked. Showing a new dialog
            // replaces it (the host shows one dialog at a time) with the real outcome.
            scope.launch {
                runCatching {
                    dialogs.showAlertDialog(
                        title = "Agent Guardrail: command not run",
                        message = "No answer in time, or the agent withdrew the request, so this command was blocked:\n\n" +
                            request.command.take(MAX_COMMAND_CHARS),
                    )
                }
            }
            throw e
        }
        return when (button) {
            DialogButton.POSITIVE -> ApprovalDecision.ALLOW_ONCE
            DialogButton.NEUTRAL -> ApprovalDecision.ALLOW_FOR_SESSION
            DialogButton.NEGATIVE, DialogButton.CANCELLED -> ApprovalDecision.DENY
        }
    }

    internal fun message(request: ActiveApprovalRequest): String = buildString {
        appendLine("An agent asked to run this command through guardrail_run:")
        appendLine()
        appendLine(request.command.take(MAX_COMMAND_CHARS) + if (request.command.length > MAX_COMMAND_CHARS) " …" else "")
        appendLine()
        appendLine("Risk: ${request.evaluation.riskLevel.name.replace('_', ' ').lowercase()}")
        request.evaluation.triggeredRules.forEach { appendLine("• ${it.name}: ${it.explanation}") }
        if (request.evaluation.triggeredRules.isEmpty()) appendLine("• ${request.evaluation.summaryExplanation}")
        appendLine()
        append("If nobody answers within ${timeout.inWholeSeconds}s the command is blocked. ")
        append("\"Allow for session\" lasts until the plugin is disabled or BOSS restarts.")
    }

    private companion object {
        const val MAX_COMMAND_CHARS = 2_000
    }
}
