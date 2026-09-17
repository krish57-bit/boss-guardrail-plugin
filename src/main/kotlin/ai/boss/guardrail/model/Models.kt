package ai.boss.guardrail.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class RiskLevel(val severity: Int) {
    SAFE(0),
    WARNING(1),
    CRITICAL_APPROVAL_REQUIRED(2);

    fun isApprovalRequired(): Boolean = this == CRITICAL_APPROVAL_REQUIRED
}

enum class ActionCategory(val displayName: String, val icon: String) {
    FILESYSTEM_DESTRUCTION("Filesystem Destruction", "🗑️"),
    DISK_FORMATTING("Disk Formatting", "💿"),
    GIT_DESTRUCTIVE("Git Destructive", "⚠️"),
    PERMISSION_ELEVATION("Permission Escalation", "🔓"),
    SYSTEM_SHUTDOWN_OR_KILL("System Shutdown/Kill", "⛔"),
    SECRET_TAMPERING("Secret Tampering", "🔑"),
    REMOTE_EXECUTION_PIPE("Remote Code Execution", "🌐"),
    DATABASE_DESTRUCTIVE("Database Destructive", "🗄️"),
    CONTAINER_DESTRUCTIVE("Container/Orchestration", "🐳"),
    NETWORK_SECURITY("Network/Firewall", "🛡️"),
    SERVICE_MANAGEMENT("Service Management", "⚙️"),
    OBFUSCATED_EXECUTION("Obfuscated Execution", "🕵️"),
    INLINE_SCRIPT("Inline Script", "📜"),
    SAFE_DEFAULT("General", "✅")
}

object RegexSerializer : KSerializer<Regex> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Regex", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Regex) {
        encoder.encodeString(value.pattern)
    }
    override fun deserialize(decoder: Decoder): Regex {
        return Regex(decoder.decodeString(), RegexOption.IGNORE_CASE)
    }
}

@Serializable
data class PolicyRule(
    val id: String,
    val name: String,
    val category: ActionCategory,
    val riskLevel: RiskLevel,
    @Serializable(with = RegexSerializer::class)
    val pattern: Regex,
    val explanation: String,
    val enabled: Boolean = true
)

data class PolicyEvaluation(
    val command: String,
    val riskLevel: RiskLevel,
    val triggeredRules: List<PolicyRule> = emptyList(),
    val summaryExplanation: String = ""
) {
    val isSafe: Boolean get() = riskLevel == RiskLevel.SAFE
    val isApprovalRequired: Boolean get() = riskLevel.isApprovalRequired()
}

enum class ApprovalDecision {
    ALLOW_ONCE,
    ALLOW_FOR_SESSION,
    DENY,
    TIMEOUT
}

sealed class ExecutionOutcome {
    data class Success(val output: String) : ExecutionOutcome()
    data class Blocked(val reason: String, val evaluation: PolicyEvaluation) : ExecutionOutcome()
    data class Failed(val error: String) : ExecutionOutcome()
}
