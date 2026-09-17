package ai.boss.guardrail

import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.boss.guardrail.model.ApprovalDecision
import ai.boss.guardrail.model.ExecutionOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class GuardrailInterceptorTest {

    private val interceptor = GuardrailInterceptor(defaultTimeout = 500.milliseconds)

    @Test
    @DisplayName("Safe commands execute without suspension")
    fun `safe command executes immediately`() = runTest {
        val outcome = interceptor.executeGuarded("s1", "git status") { "clean" }
        assertTrue(outcome is ExecutionOutcome.Success)
        assertEquals("clean", (outcome as ExecutionOutcome.Success).output)
        assertEquals(1, interceptor.auditLogs.value.size)
    }

    @Test
    @DisplayName("Dangerous command suspends and resumes on ALLOW_ONCE")
    fun `dangerous command allowed once`() = runTest {
        val deferred = async {
            interceptor.executeGuarded("s1", "rm -rf build/") { "Deleted." }
        }
        val request = interceptor.pendingApproval.filterNotNull().first()
        assertNotNull(request)
        assertEquals("rm -rf build/", request.command)
        assertTrue(interceptor.submitDecision(request.id, ApprovalDecision.ALLOW_ONCE))
        val outcome = deferred.await()
        assertTrue(outcome is ExecutionOutcome.Success)
    }

    @Test
    @DisplayName("Dangerous command blocked on DENY")
    fun `dangerous command denied`() = runTest {
        val deferred = async {
            interceptor.executeGuarded("s1", "git reset --hard") { "done" }
        }
        val request = interceptor.pendingApproval.filterNotNull().first()
        interceptor.submitDecision(request.id, ApprovalDecision.DENY)
        val outcome = deferred.await()
        assertTrue(outcome is ExecutionOutcome.Blocked)
    }

    @Test
    @DisplayName("ALLOW_FOR_SESSION caches for repeat commands")
    fun `session caching works`() = runTest {
        val first = async { interceptor.executeGuarded("st", "git clean -fdx") { "Cleaned." } }
        val request = interceptor.pendingApproval.filterNotNull().first()
        interceptor.submitDecision(request.id, ApprovalDecision.ALLOW_FOR_SESSION)
        assertTrue(first.await() is ExecutionOutcome.Success)

        val second = interceptor.executeGuarded("st", "git clean -fdx") { "Again." }
        assertTrue(second is ExecutionOutcome.Success)
        assertEquals("Again.", (second as ExecutionOutcome.Success).output)
    }

    @Test
    @DisplayName("Normalized whitespace session key matching")
    fun `session cache normalizes whitespace`() = runTest {
        val first = async { interceptor.executeGuarded("st2", "rm  -rf   build/") { "Done." } }
        val request = interceptor.pendingApproval.filterNotNull().first()
        interceptor.submitDecision(request.id, ApprovalDecision.ALLOW_FOR_SESSION)
        assertTrue(first.await() is ExecutionOutcome.Success)

        val second = interceptor.executeGuarded("st2", "rm -rf build/") { "Done again." }
        assertTrue(second is ExecutionOutcome.Success)
    }

    @Test
    @DisplayName("Timeout auto-blocks")
    fun `approval timeout blocks`() = runTest {
        val outcome = interceptor.executeGuarded("timeout", "mkfs.ext4 /dev/sdb1") { "fmt" }
        assertTrue(outcome is ExecutionOutcome.Blocked)
        assertTrue((outcome as ExecutionOutcome.Blocked).reason.contains("timed out", ignoreCase = true))
    }

    @Test
    @DisplayName("Paused guardrail refuses everything instead of passing it through")
    fun `paused guardrail refuses all`() = runTest {
        interceptor.setEnabled(false)
        var ran = 0
        val risky = interceptor.executeGuarded("s", "rm -rf /") { ran++; "passed" }
        val safe = interceptor.executeGuarded("s", "git status") { ran++; "passed" }
        assertTrue(risky is ExecutionOutcome.Blocked)
        assertTrue(safe is ExecutionOutcome.Blocked)
        assertEquals(0, ran)
        interceptor.setEnabled(true)
    }

    @Test
    @DisplayName("Cancelling the caller while waiting clears the sheet and never runs")
    fun `cancellation while pending`() = runTest {
        var ran = 0
        val job = launch { interceptor.executeGuarded("c", "rm -rf build/") { ran++; "x" } }
        val request = interceptor.pendingApproval.filterNotNull().first()
        job.cancelAndJoin()
        assertNull(interceptor.pendingApproval.value)
        assertFalse(interceptor.submitDecision(request.id, ApprovalDecision.ALLOW_ONCE), "late answer is ignored")
        assertEquals(0, ran)
    }

    @Test
    @DisplayName("WARNING-level commands also ask")
    fun `warning asks`() = runTest {
        var ran = 0
        val outcome = interceptor.executeGuarded("w", "npm install -g left-pad") { ran++; "x" }
        assertTrue(outcome is ExecutionOutcome.Blocked) // nobody answered: timed out
        assertEquals(0, ran)
    }

    @Test
    @DisplayName("A prompter that throws is treated as deny")
    fun `throwing prompter denies`() = runTest {
        val i = GuardrailInterceptor(defaultTimeout = 500.milliseconds, prompter = { error("boom") })
        var ran = 0
        val outcome = i.executeGuarded("p", "git reset --hard") { ran++; "x" }
        assertTrue(outcome is ExecutionOutcome.Blocked)
        assertEquals(ApprovalDecision.DENY, i.auditLogs.value.single().decision)
        assertEquals(0, ran)
    }

    @Test
    @DisplayName("Audit log is capped")
    fun `audit log capped`() = runTest {
        val i = GuardrailInterceptor(maxAuditEntries = 3)
        repeat(5) { i.executeGuarded("a", "echo $it") { "ok" } }
        assertEquals(listOf("echo 2", "echo 3", "echo 4"), i.auditLogs.value.map { it.command })
    }

    @Test
    @DisplayName("clearSessionAllowList revokes approvals")
    fun `clear session allowlist`() = runTest {
        val first = async { interceptor.executeGuarded("cs", "git clean -fdx") { "ok" } }
        val req = interceptor.pendingApproval.filterNotNull().first()
        interceptor.submitDecision(req.id, ApprovalDecision.ALLOW_FOR_SESSION)
        first.await()

        assertTrue(interceptor.isCommandAllowedInSession("cs", "git clean -fdx"))
        interceptor.clearSessionAllowList()
        assertFalse(interceptor.isCommandAllowedInSession("cs", "git clean -fdx"))
    }

    @Test
    @DisplayName("clearAuditLogs empties history")
    fun `clear audit logs`() = runTest {
        interceptor.executeGuarded("cl", "git status") { "ok" }
        assertTrue(interceptor.auditLogs.value.isNotEmpty())
        interceptor.clearAuditLogs()
        assertTrue(interceptor.auditLogs.value.isEmpty())
    }

    @Test
    @DisplayName("Executor exception is caught as Failed outcome")
    fun `executor failure handled`() = runTest {
        val outcome = interceptor.executeGuarded("f", "ls") { throw RuntimeException("boom") }
        assertTrue(outcome is ExecutionOutcome.Failed)
        assertEquals("boom", (outcome as ExecutionOutcome.Failed).error)
    }
}
