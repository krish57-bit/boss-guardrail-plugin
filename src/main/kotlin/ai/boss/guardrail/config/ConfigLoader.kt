package ai.boss.guardrail.config

import ai.boss.guardrail.model.PolicyRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * User settings, read from `~/.boss/plugins/config/guardrail-settings.json`.
 * Missing file or unreadable JSON means defaults.
 */
@Serializable
data class GuardrailSettings(
    /**
     * When true, the plugin turns off BOSS's own shell tools (`run_command`,
     * `run_in_sidebar`, `run_in_panel`, `send_input`) through the MCP kill-switch
     * while it is active, so agents using the BOSS MCP server have to go
     * through `guardrail_run`. Tools it turned off are turned back on when the
     * plugin is disabled or unloaded.
     */
    val strictMode: Boolean = false,
    /** Ask before every command, including ones no rule matches. */
    val requireApprovalForAll: Boolean = false,
    /**
     * How long to wait for a decision before blocking. BOSS itself cancels any MCP
     * tool call after 60 seconds (approval wait plus run time), so values above
     * that have no effect; the call is blocked either way.
     */
    val approvalTimeoutSeconds: Int = 45,
    /** Longest a single approved command may run before it is killed (also bounded by BOSS's 60s). */
    val maxCommandSeconds: Int = 55,
)

object ConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val defaultConfigDir = File(System.getProperty("user.home"), ".boss/plugins/config")

    fun loadCustomRules(configDir: File = defaultConfigDir): List<PolicyRule> {
        val file = File(configDir, "guardrail-rules.json")
        return try {
            if (file.exists()) json.decodeFromString<List<PolicyRule>>(file.readText()) else emptyList()
        } catch (e: Exception) {
            System.err.println("[BOSS Guardrail] Failed to load custom rules: ${e.message}")
            emptyList()
        }
    }

    fun saveCustomRules(rules: List<PolicyRule>, configDir: File = defaultConfigDir) {
        try {
            configDir.mkdirs()
            File(configDir, "guardrail-rules.json").writeText(json.encodeToString(rules))
        } catch (e: Exception) {
            System.err.println("[BOSS Guardrail] Failed to save custom rules: ${e.message}")
        }
    }

    fun loadSettings(configDir: File = defaultConfigDir): GuardrailSettings {
        val file = File(configDir, "guardrail-settings.json")
        return try {
            if (file.exists()) json.decodeFromString<GuardrailSettings>(file.readText()) else GuardrailSettings()
        } catch (e: Exception) {
            // Fail closed: if the operator's settings can't be read, ask about everything.
            System.err.println("[BOSS Guardrail] Failed to load settings, asking before every command: ${e.message}")
            GuardrailSettings(requireApprovalForAll = true)
        }
    }
}
