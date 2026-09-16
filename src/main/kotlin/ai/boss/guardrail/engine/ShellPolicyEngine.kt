package ai.boss.guardrail.engine

import ai.boss.guardrail.model.ActionCategory
import ai.boss.guardrail.model.PolicyEvaluation
import ai.boss.guardrail.model.PolicyRule
import ai.boss.guardrail.model.RiskLevel

class ShellPolicyEngine(
    private val customRules: List<PolicyRule> = emptyList()
) {

    private val defaultRules: List<PolicyRule> = listOf(
        // --- 1. Filesystem Destruction ---
        PolicyRule(
            id = "FS_RECURSIVE_DELETE",
            name = "Recursive File Deletion",
            category = ActionCategory.FILESYSTEM_DESTRUCTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+|--recursive\s+)""", RegexOption.IGNORE_CASE),
            explanation = "Recursive file/directory deletion (rm -r / -rf)."
        ),
        PolicyRule(
            id = "FS_ROOT_DELETE",
            name = "Root/Wildcard Deletion",
            category = ActionCategory.FILESYSTEM_DESTRUCTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\brm\s+.*(/\s*$|~\s*$|\*\s*$|\.\*\s*$)""", RegexOption.IGNORE_CASE),
            explanation = "Targets root, home, or wildcard paths for deletion."
        ),
        PolicyRule(
            id = "FS_SHRED_WIPE",
            name = "Secure File Wiping",
            category = ActionCategory.FILESYSTEM_DESTRUCTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(shred|srm|wipefs)\b""", RegexOption.IGNORE_CASE),
            explanation = "Irreversible disk/file shredding."
        ),
        PolicyRule(
            id = "FS_XARGS_RM",
            name = "Piped Deletion via xargs",
            category = ActionCategory.FILESYSTEM_DESTRUCTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bxargs\s+.*\brm\b""", RegexOption.IGNORE_CASE),
            explanation = "Batch file deletion via xargs pipe."
        ),

        // --- 2. Disk & Partition Formatting ---
        PolicyRule(
            id = "DISK_RAW_WRITE",
            name = "Raw Disk Device Write",
            category = ActionCategory.DISK_FORMATTING,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bdd\s+.*of=/dev/(sd[a-z]|nvme[0-9]|disk[0-9]|hd[a-z]|vd[a-z])""", RegexOption.IGNORE_CASE),
            explanation = "Direct raw write to disk device block via dd."
        ),
        PolicyRule(
            id = "DISK_FORMAT",
            name = "Filesystem Format Command",
            category = ActionCategory.DISK_FORMATTING,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(mkfs(\.[a-z0-9]+)?|fdisk|parted|gdisk)\b""", RegexOption.IGNORE_CASE),
            explanation = "Partition table or filesystem format command."
        ),

        // --- 3. Git Destructive Operations ---
        PolicyRule(
            id = "GIT_HARD_RESET",
            name = "Git Hard Reset",
            category = ActionCategory.GIT_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bgit\s+reset\s+--hard\b""", RegexOption.IGNORE_CASE),
            explanation = "Irreversibly discards uncommitted working tree changes."
        ),
        PolicyRule(
            id = "GIT_FORCE_CLEAN",
            name = "Git Force Clean",
            category = ActionCategory.GIT_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bgit\s+clean\s+-[a-zA-Z]*f[a-zA-Z]*\b""", RegexOption.IGNORE_CASE),
            explanation = "Permanently removes untracked files and directories."
        ),
        PolicyRule(
            id = "GIT_FORCE_PUSH",
            name = "Git Force Push",
            category = ActionCategory.GIT_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bgit\s+push\s+.*(--force|-f\b|--force-with-lease)""", RegexOption.IGNORE_CASE),
            explanation = "Overwrites remote repository history via force push."
        ),
        PolicyRule(
            id = "GIT_BRANCH_FORCE_DELETE",
            name = "Git Force Delete Branch",
            category = ActionCategory.GIT_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bgit\s+branch\s+-[a-zA-Z]*D[a-zA-Z]*\b""", RegexOption.IGNORE_CASE),
            explanation = "Forcibly deletes branch regardless of merge status."
        ),
        PolicyRule(
            id = "GIT_DISCARD_WORKTREE",
            name = "Git Discard All Changes",
            category = ActionCategory.GIT_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bgit\s+(restore|checkout)\s+(\.|\*|--\s+\.)(\s+|$)""", RegexOption.IGNORE_CASE),
            explanation = "Mass discards local workspace modifications."
        ),

        // --- 4. Permission & Access Escalation ---
        PolicyRule(
            id = "PERM_WIDE_OPEN",
            name = "World-Writable Permissions",
            category = ActionCategory.PERMISSION_ELEVATION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bchmod\s+(-[a-zA-Z]*R[a-zA-Z]*\s+)?(777|a\+[rwx]{3}|666)\b""", RegexOption.IGNORE_CASE),
            explanation = "Grants global read/write/execute permissions."
        ),
        PolicyRule(
            id = "PERM_SUDO_SU",
            name = "Superuser Shell Escalation",
            category = ActionCategory.PERMISSION_ELEVATION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(sudo\s+su\b|sudo\s+-i\b|su\s+-?\s*$)""", RegexOption.IGNORE_CASE),
            explanation = "Spawning an unrestricted root/superuser shell."
        ),
        PolicyRule(
            id = "PERM_CHOWN_RECURSIVE_ROOT",
            name = "Recursive Ownership to Root",
            category = ActionCategory.PERMISSION_ELEVATION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bchown\s+(-[a-zA-Z]*R[a-zA-Z]*\s+).*\b(root|0)(\s*:\s*(root|0))?\s+/""", RegexOption.IGNORE_CASE),
            explanation = "Recursive ownership change to root on system paths."
        ),

        // --- 5. System Shutdown, Kill, & Fork Bomb ---
        // FIXED: Position-aware — must be first token (after optional sudo) to avoid false positives on grep/echo
        PolicyRule(
            id = "SYS_SHUTDOWN_REBOOT",
            name = "System Shutdown or Reboot",
            category = ActionCategory.SYSTEM_SHUTDOWN_OR_KILL,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(^|\b(sudo|doas)\s+)\b(shutdown|reboot|poweroff|halt)\b""", RegexOption.IGNORE_CASE),
            explanation = "Halts or restarts the operating system."
        ),
        PolicyRule(
            id = "SYS_INIT_LEVEL",
            name = "System Init Level Change",
            category = ActionCategory.SYSTEM_SHUTDOWN_OR_KILL,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(^|\b(sudo|doas)\s+)\binit\s+[06]\b""", RegexOption.IGNORE_CASE),
            explanation = "Changing init level to halt or reboot."
        ),
        PolicyRule(
            id = "SYS_MASS_KILL",
            name = "Mass Process Kill",
            category = ActionCategory.SYSTEM_SHUTDOWN_OR_KILL,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(killall|pkill)\s+(-9\s+|-s\s+9\s+|--signal\s+(SIGKILL|9)\s+)""", RegexOption.IGNORE_CASE),
            explanation = "Forcefully terminating process groups via SIGKILL."
        ),
        PolicyRule(
            id = "SYS_FORK_BOMB",
            name = "Fork Bomb",
            category = ActionCategory.SYSTEM_SHUTDOWN_OR_KILL,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(:\s*\(\s*\)\s*\{\s*:\|:&\s*\};:|fork\s+while\s+true)""", RegexOption.IGNORE_CASE),
            explanation = "Exhausts system process table via recursive fork bomb."
        ),

        // --- 6. Remote Pipe to Shell ---
        PolicyRule(
            id = "NET_PIPE_TO_SHELL",
            name = "Remote Script Piped to Shell",
            category = ActionCategory.REMOTE_EXECUTION_PIPE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(curl|wget|fetch|http|aria2c)\s+.*\|\s*(sudo\s+)?(bash|sh|zsh|dash|ksh|python[0-9]*|perl|ruby)\b""", RegexOption.IGNORE_CASE),
            explanation = "Executing unverified remote script in a shell."
        ),
        PolicyRule(
            id = "NET_BASE64_PIPE",
            name = "Encoded Payload Piped to Shell",
            category = ActionCategory.REMOTE_EXECUTION_PIPE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bbase64\s+(-d|--decode)\s*\|\s*(sudo\s+)?(bash|sh|zsh|python[0-9]*|perl)\b""", RegexOption.IGNORE_CASE),
            explanation = "Decoding and executing an encoded payload."
        ),

        // --- 7. Secret & Key Tampering ---
        PolicyRule(
            id = "SECRET_CREDENTIAL_ACCESS",
            name = "Credential File Access",
            category = ActionCategory.SECRET_TAMPERING,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(>\s*|\bcat\s+|\bcp\s+|\bmv\s+|\brm\s+).*(\.env(\.[a-zA-Z0-9_-]+)?|id_rsa|id_ed25519|id_ecdsa|\.aws/credentials|\.kube/config|/etc/shadow|/etc/sudoers)""", RegexOption.IGNORE_CASE),
            explanation = "Access or modification to keys, credentials, or secrets."
        ),

        // --- 8. Database Destructive Operations ---
        PolicyRule(
            id = "DB_DROP_TRUNCATE",
            name = "Database Drop/Truncate",
            category = ActionCategory.DATABASE_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(DROP\s+DATABASE|DROP\s+TABLE|TRUNCATE\s+TABLE|DROP\s+SCHEMA)\b""", RegexOption.IGNORE_CASE),
            explanation = "Destructive database schema alteration or truncation."
        ),
        PolicyRule(
            id = "DB_DELETE_ALL",
            name = "Mass Row Deletion (no WHERE)",
            category = ActionCategory.DATABASE_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bDELETE\s+FROM\s+\w+\s*;\s*""", RegexOption.IGNORE_CASE),
            explanation = "DELETE FROM without WHERE clause wipes entire table."
        ),

        // --- 9. Container & Orchestration Destructive ---
        PolicyRule(
            id = "DOCKER_SYSTEM_PRUNE",
            name = "Docker System Prune",
            category = ActionCategory.CONTAINER_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bdocker\s+(system\s+prune|volume\s+prune|image\s+prune|container\s+prune)\b.*(-a|--all|-f|--force)""", RegexOption.IGNORE_CASE),
            explanation = "Bulk removal of Docker resources."
        ),
        PolicyRule(
            id = "KUBECTL_DELETE_NS",
            name = "Kubernetes Namespace Deletion",
            category = ActionCategory.CONTAINER_DESTRUCTIVE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bkubectl\s+delete\s+(namespace|ns|deploy|deployment|service|svc|pod|pv|pvc)\b""", RegexOption.IGNORE_CASE),
            explanation = "Deleting Kubernetes resources."
        ),

        // --- 10. Network & Firewall ---
        PolicyRule(
            id = "NET_IPTABLES_FLUSH",
            name = "Firewall Rules Flush",
            category = ActionCategory.NETWORK_SECURITY,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(iptables|ip6tables|nft|ufw)\s+.*(-F|--flush|disable|reset)\b""", RegexOption.IGNORE_CASE),
            explanation = "Flushing or disabling firewall rules."
        ),

        // --- 11. Service Management ---
        PolicyRule(
            id = "SVC_STOP_DISABLE",
            name = "Critical Service Stop/Disable",
            category = ActionCategory.SERVICE_MANAGEMENT,
            riskLevel = RiskLevel.WARNING,
            pattern = Regex("""\b(systemctl|service)\s+(stop|disable|mask)\s+\w+""", RegexOption.IGNORE_CASE),
            explanation = "Stopping or disabling a system service."
        ),

        // --- 12. Warnings (Medium Risk) ---
        PolicyRule(
            id = "WARN_NPM_GLOBAL_INSTALL",
            name = "Global Package Installation",
            category = ActionCategory.SAFE_DEFAULT,
            riskLevel = RiskLevel.WARNING,
            pattern = Regex("""\b(npm|yarn|pnpm)\s+(install|i|add)\s+.*(-g|--global)\b""", RegexOption.IGNORE_CASE),
            explanation = "Global system package installation."
        ),
        PolicyRule(
            id = "WARN_PIP_ROOT_INSTALL",
            name = "Unrestricted pip Install",
            category = ActionCategory.SAFE_DEFAULT,
            riskLevel = RiskLevel.WARNING,
            pattern = Regex("""\bpip(3)?\s+install\s+.*(--break-system-packages)\b""", RegexOption.IGNORE_CASE),
            explanation = "Installing packages breaking system manager bounds."
        ),
        PolicyRule(
            id = "WARN_HISTORY_CLEAR",
            name = "Shell History Clearing",
            category = ActionCategory.SAFE_DEFAULT,
            riskLevel = RiskLevel.WARNING,
            pattern = Regex("""\b(history\s+-c|history\s+-w\s*/dev/null)\b""", RegexOption.IGNORE_CASE),
            explanation = "Clearing shell command history."
        )
    )

    val allRules: List<PolicyRule> get() = defaultRules + customRules

    val enabledRules: List<PolicyRule> get() = allRules.filter { it.enabled }

    fun evaluate(rawCommand: String): PolicyEvaluation {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            return PolicyEvaluation(command = rawCommand, riskLevel = RiskLevel.SAFE, summaryExplanation = "Empty command.")
        }

        val segments = splitCommands(trimmed)
        val triggered = mutableListOf<PolicyRule>()

        for (segment in segments) {
            for (rule in enabledRules) {
                if (rule.pattern.containsMatchIn(segment) && triggered.none { it.id == rule.id }) {
                    triggered.add(rule)
                }
            }
        }

        val maxRisk = triggered.maxByOrNull { it.riskLevel.severity }?.riskLevel ?: RiskLevel.SAFE
        val explanation = if (triggered.isNotEmpty()) {
            triggered.joinToString(" | ") { "[${it.name}]: ${it.explanation}" }
        } else {
            "Command evaluated as safe."
        }

        return PolicyEvaluation(command = rawCommand, riskLevel = maxRisk, triggeredRules = triggered, summaryExplanation = explanation)
    }

    /**
     * Splits compound commands for independent evaluation.
     * 
     * Key design decision: pipes (|) are NOT split as independent commands
     * because piped segments share execution context. Instead, the full
     * pipeline is evaluated as a unit, which allows rules like
     * "curl ... | bash" to match correctly.
     * 
     * We DO split on: && (and-then), || (or-else), ; (sequence), newlines.
     * We DO extract: $(...) subshells and `backtick` subshells.
     */
    internal fun splitCommands(input: String): List<String> {
        val results = mutableListOf<String>()
        results.add(input) // Always evaluate full input

        // Extract subshells: $(...) and `...`
        val subshellRegex = Regex("""\$\((.*?)\)|`([^`]+)`""")
        subshellRegex.findAll(input).forEach { match ->
            val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (inner.isNotBlank()) results.add(inner.trim())
        }

        // Split ONLY by sequential operators, NOT pipe
        val parts = input.split(Regex("""(&&|\|\||;|\n)"""))
        for (part in parts) {
            val t = part.trim()
            if (t.isNotEmpty() && t != input && !results.contains(t)) {
                results.add(t)
            }
        }

        return results
    }

    companion object {
        /**
         * Normalizes whitespace for consistent session key matching.
         */
        fun normalizeCommand(command: String): String {
            return command.trim().replace(Regex("""\s+"""), " ")
        }
    }
}
