package ai.boss.guardrail.interceptor

import ai.boss.guardrail.engine.ShellPolicyEngine
import ai.boss.guardrail.model.ApprovalDecision
import ai.boss.guardrail.model.ExecutionOutcome
import ai.boss.guardrail.model.PolicyEvaluation
import ai.boss.guardrail.model.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

class GuardrailInterceptor(
    val policyEngine: ShellPolicyEngine = ShellPolicyEngine(),
    private val defaultTimeout: Duration = 60.seconds
) {
    // --- Reactive State ---
    private val _pendingApproval = MutableStateFlow<ActiveApprovalRequest?>(null)
    val pendingApproval: StateFlow<ActiveApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // --- Session Allowlist ---
    private val sessionAllowList = ConcurrentHashMap.newKeySet<String>()

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
    }

    fun isCommandAllowedInSession(sessionId: String, command: String): Boolean {
        return sessionAllowList.contains(sessionKey(sessionId, command))
    }

    suspend fun executeGuarded(
        sessionId: String,
        command: String,
        executor: suspend (String) -> String
    ): ExecutionOutcome {

        // If guardrail is disabled, pass everything through
        if (!_isEnabled.value) {
            return runAndAudit(sessionId, command,
                PolicyEvaluation(command, RiskLevel.SAFE, summaryExplanation = "Guardrail disabled."),
                null, executor
            )
        }

        val eval = policyEngine.evaluate(command)

        if (eval.isSafe) {
            return runAndAudit(sessionId, command, eval, null, executor)
        }

        // Check session whitelist (normalized)
        if (isCommandAllowedInSession(sessionId, command)) {
            return runAndAudit(sessionId, command, eval, ApprovalDecision.ALLOW_FOR_SESSION, executor)
        }

        // Suspend and await human decision via CompletableDeferred
        val request = ActiveApprovalRequest(
            sessionId = sessionId,
            command = command,
            evaluation = eval
        )
        _pendingApproval.value = request

        val decision = withTimeoutOrNull(defaultTimeout) {
            request.deferred.await()
        } ?: ApprovalDecision.TIMEOUT

        _pendingApproval.value = null

        return when (decision) {
            ApprovalDecision.ALLOW_ONCE -> {
                runAndAudit(sessionId, command, eval, decision, executor)
            }
            ApprovalDecision.ALLOW_FOR_SESSION -> {
                sessionAllowList.add(sessionKey(sessionId, command))
                runAndAudit(sessionId, command, eval, decision, executor)
            }
            ApprovalDecision.DENY -> {
                val outcome = ExecutionOutcome.Blocked(
                    reason = "Command blocked by operator: ${eval.summaryExplanation}",
                    evaluation = eval
                )
                recordAudit(sessionId, command, eval.riskLevel, decision, outcome)
                outcome
            }
            ApprovalDecision.TIMEOUT -> {
                val outcome = ExecutionOutcome.Blocked(
                    reason = "Command approval timed out after ${defaultTimeout.inWholeSeconds}s.",
                    evaluation = eval
                )
                recordAudit(sessionId, command, eval.riskLevel, decision, outcome)
                outcome
            }
        }
    }

    fun submitDecision(requestId: String, decision: ApprovalDecision): Boolean {
        val current = _pendingApproval.value
        return if (current != null && current.id == requestId) {
            current.deferred.complete(decision)
            true
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

    private suspend fun runAndAudit(
        sessionId: String,
        command: String,
        eval: PolicyEvaluation,
        decision: ApprovalDecision?,
        executor: suspend (String) -> String
    ): ExecutionOutcome {
        return try {
            val output = executor(command)
            val outcome = ExecutionOutcome.Success(output)
            recordAudit(sessionId, command, eval.riskLevel, decision, outcome)
            outcome
        } catch (e: Throwable) {
            val outcome = ExecutionOutcome.Failed(e.message ?: "Unknown execution failure")
            recordAudit(sessionId, command, eval.riskLevel, decision, outcome)
            outcome
        }
    }

    private fun recordAudit(
        sessionId: String,
        command: String,
        risk: RiskLevel,
        decision: ApprovalDecision?,
        outcome: ExecutionOutcome
    ) {
        _auditLogs.update { current ->
            current + AuditLogEntry(
                sessionId = sessionId,
                command = command,
                riskLevel = risk,
                decision = decision,
                outcome = outcome
            )
        }
    }

    private fun sessionKey(sessionId: String, command: String): String {
        return "$sessionId:${ShellPolicyEngine.normalizeCommand(command)}"
    }
}
