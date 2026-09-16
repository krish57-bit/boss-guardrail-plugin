package ai.boss.guardrail

import ai.boss.guardrail.config.ConfigLoader
import ai.boss.guardrail.engine.ShellPolicyEngine
import ai.boss.guardrail.interceptor.GuardrailInterceptor

class GuardrailPlugin {

    val id: String = "ai.boss.guardrail"
    val version: String = "1.0.0"
    
    lateinit var policyEngine: ShellPolicyEngine
    lateinit var interceptor: GuardrailInterceptor

    private var isEnabled: Boolean = false

    fun onLoad() {
        println("[BOSS Guardrail] Loading custom rules...")
        val customRules = ConfigLoader.loadCustomRules()
        policyEngine = ShellPolicyEngine(customRules)
        interceptor = GuardrailInterceptor(policyEngine = policyEngine)
        
        println("[BOSS Guardrail] Plugin loaded successfully (v$version) with ${customRules.size} custom rules.")
    }

    fun onEnable() {
        isEnabled = true
        if (::interceptor.isInitialized) {
            interceptor.setEnabled(true)
        }
        println("[BOSS Guardrail] Agent safety interceptor activated.")
    }

    fun onDisable() {
        isEnabled = false
        if (::interceptor.isInitialized) {
            interceptor.setEnabled(false)
        }
        println("[BOSS Guardrail] Agent safety interceptor deactivated.")
    }

    fun getStatus(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "version" to version,
            "enabled" to isEnabled,
            "totalAuditedActions" to if (::interceptor.isInitialized) interceptor.auditLogs.value.size else 0
        )
    }
}
