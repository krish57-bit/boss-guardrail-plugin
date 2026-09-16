package ai.boss.guardrail.config

import ai.boss.guardrail.model.PolicyRule
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object ConfigLoader {
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
    private val configDir = File(System.getProperty("user.home"), ".boss/plugins/config")
    private val customRulesFile = File(configDir, "guardrail-rules.json")

    fun loadCustomRules(): List<PolicyRule> {
        return try {
            if (customRulesFile.exists()) {
                val content = customRulesFile.readText()
                json.decodeFromString<List<PolicyRule>>(content)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("[BOSS Guardrail] Failed to load custom rules: ${e.message}")
            emptyList()
        }
    }

    fun saveCustomRules(rules: List<PolicyRule>) {
        try {
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val content = json.encodeToString(rules)
            customRulesFile.writeText(content)
        } catch (e: Exception) {
            println("[BOSS Guardrail] Failed to save custom rules: ${e.message}")
        }
    }
}
