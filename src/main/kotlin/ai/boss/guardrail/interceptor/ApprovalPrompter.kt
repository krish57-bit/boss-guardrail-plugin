package ai.boss.guardrail.interceptor

import ai.boss.guardrail.model.ApprovalDecision

/**
 * Asks a human whether a flagged command may run.
 *
 * Implementations must suspend until the human answers. They must never
 * return [ApprovalDecision.ALLOW_ONCE] or [ApprovalDecision.ALLOW_FOR_SESSION]
 * unless a person actually chose it: anything else (dialog dismissed, no UI
 * available, an error) has to come back as [ApprovalDecision.DENY].
 *
 * Timeouts are applied by [GuardrailInterceptor], not here, so a prompter that
 * never returns is safe: the interceptor cancels it and blocks the command.
 */
fun interface ApprovalPrompter {
    suspend fun ask(request: ActiveApprovalRequest): ApprovalDecision
}
