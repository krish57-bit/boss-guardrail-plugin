package ai.boss.guardrail.interceptor

import ai.boss.guardrail.engine.ShellPolicyEngine
import ai.boss.guardrail.model.ApprovalDecision
import ai.boss.guardrail.model.ExecutionOutcome
import ai.boss.guardrail.model.PolicyEvaluation
import ai.boss.guardrail.model.RiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ActiveApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val command: String,
    val evaluation: PolicyEvaluation,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<ApprovalDecision> = CompletableDeferred()
)

data class AuditLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionId: String,
    val command: String,
    val riskLevel: RiskLevel,
    val decision: ApprovalDecision?,
    val outcome: ExecutionOutcome
)

/**
 * Evaluates a command, asks a human when a rule requires it, and only then
 * hands the command to the executor.
 *
 * Any rule match (WARNING or CRITICAL) needs approval; only SAFE runs directly.
 *
 * Guarantees (each covered by tests):
 * - DENY, TIMEOUT, a dismissed dialog, a missing UI and caller cancellation
 *   never reach the executor.
 * - ALLOW_ONCE runs the executor exactly once and caches nothing.
 * - ALLOW_FOR_SESSION caches the normalized command for this interceptor's
 *   lifetime (in BOSS: until the plugin is disabled or unloaded).
 * - When the guardrail is paused, commands are refused, not passed through.
 *
 * Only one approval is shown at a time; concurrent flagged commands queue.
 *
 * @param prompter how to ask the human. When null, requests are published on
 *   [pendingApproval] and answered through [submitDecision] (used by the
 *   standalone app's Compose sheet and by tests).
 */
class GuardrailInterceptor(
    val policyEngine: ShellPolicyEngine = ShellPolicyEngine(),
    private val defaultTimeout: Duration = 60.seconds,
    private val prompter: ApprovalPrompter? = null,
    private val maxAuditEntries: Int = 500,
) {
    private val _pendingApproval = MutableStateFlow<ActiveApprovalRequest?>(null)
    val pendingApproval: StateFlow<ActiveApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val sessionAllowList = ConcurrentHashMap.newKeySet<String>()
    private val promptLock = Mutex()

    val timeout: Duration get() = defaultTimeout

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    fun isCommandAllowedInSession(sessionId: String, command: String): Boolean =
        sessionAllowList.contains(sessionKey(sessionId, command))

    suspend fun executeGuarded(
        sessionId: String,
        command: String,
        executor: suspend (String) -> String
    ): ExecutionOutcome {
        val eval = policyEngine.evaluate(command)

        if (!_isEnabled.value) {
            return block(sessionId, command, eval, null, "Guardrail is paused, so it is refusing all commands. Re-enable it in the Agent Guardrail panel.")
        }

        if (eval.isSafe) {
            return runAndAudit(sessionId, command, eval, null, executor)
        }

        if (isCommandAllowedInSession(sessionId, command)) {
            return runAndAudit(sessionId, command, eval, ApprovalDecision.ALLOW_FOR_SESSION, executor)
        }

        val request = ActiveApprovalRequest(sessionId = sessionId, command = command, evaluation = eval)
        val decision = promptLock.withLock {
            // Another queued request may have granted this exact command for the session.
            if (isCommandAllowedInSession(sessionId, command)) {
                ApprovalDecision.ALLOW_FOR_SESSION
            } else {
                awaitDecision(request)
            }
        }

        return when (decision) {
            ApprovalDecision.ALLOW_ONCE -> runAndAudit(sessionId, command, eval, decision, executor)
            ApprovalDecision.ALLOW_FOR_SESSION -> {
                sessionAllowList.add(sessionKey(sessionId, command))
                runAndAudit(sessionId, command, eval, decision, executor)
            }
            ApprovalDecision.DENY ->
                block(sessionId, command, eval, decision, "Command blocked by operator: ${eval.summaryExplanation}")
            ApprovalDecision.TIMEOUT ->
                block(sessionId, command, eval, decision, "Command approval timed out after ${defaultTimeout.inWholeSeconds}s.")
        }
    }

    private suspend fun awaitDecision(request: ActiveApprovalRequest): ApprovalDecision {
        val active = prompter
        return if (active != null) {
            val answer = try {
                withTimeoutOrNull(defaultTimeout) { active.ask(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ApprovalDecision.DENY // a broken prompt must never read as consent
            }
            answer ?: ApprovalDecision.TIMEOUT
        } else {
            _pendingApproval.value = request
            try {
                withTimeoutOrNull(defaultTimeout) { request.deferred.await() } ?: ApprovalDecision.TIMEOUT
            } finally {
                // Also runs on cancellation, so a stale sheet never outlives its caller.
                _pendingApproval.compareAndSet(request, null)
                request.deferred.cancel()
            }
        }
    }

    fun submitDecision(requestId: String, decision: ApprovalDecision): Boolean {
        val current = _pendingApproval.value
        return if (current != null && current.id == requestId) {
            current.deferred.complete(decision)
        } else {
            false
        }
    }

    fun clearSessionAllowList() {
        sessionAllowList.clear()
    }

    fun clearAuditLogs() {
        _auditLogs.value = emptyList()
    }

    private fun block(
        sessionId: String,
        command: String,
        eval: PolicyEvaluation,
        decision: ApprovalDecision?,
        reason: String,
    ): ExecutionOutcome {
        val outcome = ExecutionOutcome.Blocked(reason = reason, evaluation = eval)
        recordAudit(sessionId, command, eval.riskLevel, decision, outcome)
        return outcome
    }

    private suspend fun runAndAudit(
        sessionId: String,
        command: String,
        eval: PolicyEvaluation,
        decision: ApprovalDecision?,
        executor: suspend (String) -> String
    ): ExecutionOutcome {
        val outcome = try {
            ExecutionOutcome.Success(executor(command))
        } catch (e: CancellationException) {
            recordAudit(sessionId, command, eval.riskLevel, decision, ExecutionOutcome.Failed("Cancelled while running."))
            throw e
        } catch (e: Throwable) {
            ExecutionOutcome.Failed(e.message ?: "Unknown execution failure")
        }
        recordAudit(sessionId, command, eval.riskLevel, decision, outcome)
        return outcome
    }

    private fun recordAudit(
        sessionId: String,
        command: String,
        risk: RiskLevel,
        decision: ApprovalDecision?,
        outcome: ExecutionOutcome
    ) {
        val entry = AuditLogEntry(sessionId = sessionId, command = command, riskLevel = risk, decision = decision, outcome = outcome)
        _auditLogs.update { (it + entry).takeLast(maxAuditEntries) }
    }

    private fun sessionKey(sessionId: String, command: String): String =
        "$sessionId:${ShellPolicyEngine.normalizeCommand(command)}"
}
