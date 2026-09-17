package ai.boss.guardrail.boss

import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.boss.guardrail.ui.GuardrailPanel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext

object GuardrailPanelInfo : PanelInfo {
    override val id = PanelId("agent-guardrail", 70, GuardrailDynamicPlugin.PLUGIN_ID)
    override val displayName = "Agent Guardrail"
    override val icon: ImageVector get() = Icons.Filled.Shield
    override val defaultSlotPosition = right.bottom
}

class GuardrailPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val interceptor: GuardrailInterceptor,
    private val statusNote: () -> String,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        GuardrailPanel(interceptor = interceptor, statusNote = statusNote())
    }
}
