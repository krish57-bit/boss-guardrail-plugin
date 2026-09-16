package ai.boss.guardrail.ui

import ai.boss.guardrail.config.ConfigLoader
import ai.boss.guardrail.engine.ShellPolicyEngine
import ai.boss.guardrail.interceptor.GuardrailInterceptor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val customRules = ConfigLoader.loadCustomRules()
    val interceptor = GuardrailInterceptor(policyEngine = ShellPolicyEngine(customRules))
    val windowState = rememberWindowState(width = 1100.dp, height = 750.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "BOSS Agent Guardrail & Policy Interceptor",
        state = windowState
    ) {
        GuardrailPanel(interceptor = interceptor)
    }
}
