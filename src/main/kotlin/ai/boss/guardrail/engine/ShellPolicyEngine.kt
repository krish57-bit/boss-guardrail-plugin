package ai.boss.guardrail.engine

import ai.boss.guardrail.model.ActionCategory
import ai.boss.guardrail.model.PolicyEvaluation
import ai.boss.guardrail.model.PolicyRule
import ai.boss.guardrail.model.RiskLevel

/**
 * Pattern-matching classifier for shell commands.
 *
 * This is best-effort. A determined agent can always write a command these
 * patterns do not recognise, which is why [requireApprovalForAll] exists and
 * why the guardrail only covers commands sent through its own `guardrail_run`
 * tool (see README "What this does not protect").
 *
 * @param requireApprovalForAll when true, commands that match no rule are
 *   returned as [RiskLevel.WARNING] instead of SAFE, so every command asks.
 */
class ShellPolicyEngine(
    private val customRules: List<PolicyRule> = emptyList(),
    private val requireApprovalForAll: Boolean = false,
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
            pattern = Regex("""\bdocker\s+(system|volume|image|container|network|builder|buildx)\s+prune\b""", RegexOption.IGNORE_CASE),
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


        // --- 13. Obfuscation: commands the patterns above cannot see through ---
        PolicyRule(
            id = "OBF_EVAL",
            name = "eval of Dynamic Code",
            category = ActionCategory.OBFUSCATED_EXECUTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(^|[;&|({]\s*|\s)eval\s""", RegexOption.IGNORE_CASE),
            explanation = "eval runs a string built at runtime, so its real command cannot be checked."
        ),
        PolicyRule(
            id = "OBF_VARIABLE_COMMAND",
            name = "Command Name From Variable",
            category = ActionCategory.OBFUSCATED_EXECUTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(^|[;&|({]\s*|\b(sudo|env|exec|command|nohup|xargs)\s+)["']?\$\{?[A-Za-z_][A-Za-z0-9_]*"""),
            explanation = "The program to run comes from a variable, so it cannot be checked (e.g. R=rm; \$R -rf /)."
        ),
        PolicyRule(
            id = "OBF_SHELL_C_DYNAMIC",
            name = "Nested Shell With Substitution",
            category = ActionCategory.OBFUSCATED_EXECUTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(ba|z|da|k|fi)?sh\s+(-[a-z]*c[a-z]*)\s+.*(\$\(|`|\$\{?[A-Za-z_]|<<<|base64|printf|xxd)""", RegexOption.IGNORE_CASE),
            explanation = "A nested shell runs text generated at runtime."
        ),
        PolicyRule(
            id = "OBF_DECODE_TO_SHELL",
            name = "Decoded Payload Executed",
            category = ActionCategory.OBFUSCATED_EXECUTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(base64\s+(-d|-D|--decode)|xxd\s+-r|openssl\s+(enc\s+)?.*-d\b|\brev\s).*(\|\s*(sudo\s+)?((ba|z|da|k)?sh|python[0-9.]*|perl|ruby|node)\b)""", RegexOption.IGNORE_CASE),
            explanation = "Decodes hidden text and runs it."
        ),
        PolicyRule(
            id = "OBF_PROCESS_SUBSTITUTION_SHELL",
            name = "Shell Reading From Process Substitution",
            category = ActionCategory.REMOTE_EXECUTION_PIPE,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b((ba|z|da|k)?sh|source|\.)\s+<\(""", RegexOption.IGNORE_CASE),
            explanation = "Runs the output of another command as a script."
        ),
        PolicyRule(
            id = "OBF_QUOTE_SPLIT",
            name = "Quote or Backslash Inside Command Name",
            category = ActionCategory.OBFUSCATED_EXECUTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""(^|[;&|({]\s*|\bsudo\s+)(\\[A-Za-z]|[A-Za-z]*(''|""|'[A-Za-z]+'|"[A-Za-z]+")[A-Za-z]+|[A-Za-z]+\\[A-Za-z])"""),
            explanation = "Quotes or backslashes inside a program name are a common way to hide it (e.g. r''m, \\rm)."
        ),

        // --- 14. Destructive equivalents that do not use rm ---
        PolicyRule(
            id = "FS_FIND_DELETE",
            name = "find -delete",
            category = ActionCategory.FILESYSTEM_DESTRUCTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\bfind\b.*\s-delete\b""", RegexOption.IGNORE_CASE),
            explanation = "Deletes every file find matches."
        ),
        PolicyRule(
            id = "FS_TRUNCATE_ZERO",
            name = "Truncate File To Zero",
            category = ActionCategory.FILESYSTEM_DESTRUCTION,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\btruncate\b.*(-s\s*0|--size[= ]0)\b""", RegexOption.IGNORE_CASE),
            explanation = "Empties file contents."
        ),
        PolicyRule(
            id = "FS_REDIRECT_TO_DEVICE",
            name = "Redirect Into Disk Device",
            category = ActionCategory.DISK_FORMATTING,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex(""">\s*/dev/(sd[a-z]|nvme[0-9]|disk[0-9]|hd[a-z]|vd[a-z]|mmcblk[0-9])""", RegexOption.IGNORE_CASE),
            explanation = "Writes straight over a disk device."
        ),
        PolicyRule(
            id = "DISK_DD_ANY_OUTPUT",
            name = "dd Writing a File",
            category = ActionCategory.DISK_FORMATTING,
            riskLevel = RiskLevel.WARNING,
            pattern = Regex("""\bdd\b.*\bof=""", RegexOption.IGNORE_CASE),
            explanation = "dd overwrites its output without asking."
        ),
        PolicyRule(
            id = "SCRIPT_INLINE_DESTRUCTIVE",
            name = "Inline Script Deleting Files Or Spawning Shells",
            category = ActionCategory.INLINE_SCRIPT,
            riskLevel = RiskLevel.CRITICAL_APPROVAL_REQUIRED,
            pattern = Regex("""\b(python[0-9.]*|node|deno|bun|perl|ruby|php)\s+(-[a-zA-Z]*[ceE]\b|--eval\b).*(rmtree|os\.remove|os\.unlink|unlink|rmSync|rmdirSync|rm_rf|remove_dir|child_process|subprocess|os\.system|system\s*\(|exec\s*\(|spawn|File\.delete|FileUtils)""", RegexOption.IGNORE_CASE),
            explanation = "A one-line script that deletes files or runs other programs."
        ),
        PolicyRule(
            id = "WARN_ALIAS_DEFINITION",
            name = "Alias Or Function Definition",
            category = ActionCategory.OBFUSCATED_EXECUTION,
            riskLevel = RiskLevel.WARNING,
            pattern = Regex("""(\balias\s+[A-Za-z_][\w-]*=|^\s*(function\s+)?[A-Za-z_][\w-]*\s*\(\s*\)\s*\{)"""),
            explanation = "Defines a new name for a command, which later checks cannot follow."
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

        val matchedRisk = triggered.maxByOrNull { it.riskLevel.severity }?.riskLevel
        val maxRisk = matchedRisk ?: if (requireApprovalForAll) RiskLevel.WARNING else RiskLevel.SAFE
        val explanation = when {
            triggered.isNotEmpty() -> triggered.joinToString(" | ") { "[${it.name}]: ${it.explanation}" }
            requireApprovalForAll -> "No rule matched, but settings require approval for every command."
            else -> "No rule matched."
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
