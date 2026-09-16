package ai.boss.guardrail.ui

import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.boss.guardrail.model.ApprovalDecision
import ai.boss.guardrail.model.ExecutionOutcome
import ai.boss.guardrail.model.RiskLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun GuardrailPanel(
    interceptor: GuardrailInterceptor,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val isShieldActive by interceptor.isEnabled.collectAsState()
    val logs by interceptor.auditLogs.collectAsState()

    val totalEvaluated = logs.size
    val blockedCount = logs.count { it.outcome is ExecutionOutcome.Blocked }
    val sessionAllowedCount = logs.count { it.decision == ApprovalDecision.ALLOW_FOR_SESSION }
    val safeCount = logs.count { it.riskLevel == RiskLevel.SAFE }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF11111B))) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Guardrail Shield",
                        tint = if (isShieldActive) Color(0xFFA6E3A1) else Color(0xFFF38BA8),
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text("Agent Safety & Policy Interceptor", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Governing AI Agent terminal execution in BOSS Console", fontSize = 12.sp, color = Color(0xFFA6ADC8))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isShieldActive) "ACTIVE" else "DISABLED",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (isShieldActive) Color(0xFFA6E3A1) else Color(0xFFF38BA8)
                    )
                    Switch(
                        checked = isShieldActive,
                        onCheckedChange = { interceptor.setEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFA6E3A1), checkedTrackColor = Color(0xFFA6E3A1).copy(0.5f),
                            uncheckedThumbColor = Color(0xFFF38BA8), uncheckedTrackColor = Color(0xFFF38BA8).copy(0.5f)
                        )
                    )
                }
            }

            // Stats
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Total Inspected", totalEvaluated.toString(), Icons.Default.Terminal, Color(0xFF89B4FA), Modifier.weight(1f))
                MetricCard("Blocked", blockedCount.toString(), Icons.Default.Security, Color(0xFFF38BA8), Modifier.weight(1f))
                MetricCard("Session OK", sessionAllowedCount.toString(), Icons.Default.VerifiedUser, Color(0xFFFAB387), Modifier.weight(1f))
                MetricCard("Auto-Passed", safeCount.toString(), Icons.Default.CheckCircle, Color(0xFFA6E3A1), Modifier.weight(1f))
            }

            // Tabs
            TabRow(selectedTabIndex = selectedTab, backgroundColor = Color(0xFF1E1E2E), contentColor = Color(0xFF89B4FA), modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Sandbox", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Audit Log", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    text = { Text("Policy Rules", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Shield, contentDescription = null) })
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> SandboxTesterTab(interceptor = interceptor)
                    1 -> AuditLogViewer(interceptor = interceptor)
                    2 -> PolicyRulesViewer(engine = interceptor.policyEngine)
                }
            }
        }

        // Live Approval Modal Overlay
        GuardrailApprovalSheet(interceptor = interceptor)
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: ImageVector, accentColor: Color, modifier: Modifier = Modifier) {
    Card(backgroundColor = Color(0xFF1E1E2E), shape = RoundedCornerShape(12.dp), modifier = modifier.border(1.dp, Color(0xFF313244), RoundedCornerShape(12.dp))) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(accentColor.copy(0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(title, fontSize = 11.sp, color = Color(0xFFA6ADC8), fontWeight = FontWeight.Medium)
                Text(value, fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SandboxTesterTab(interceptor: GuardrailInterceptor) {
    var commandInput by remember { mutableStateOf("rm -rf /var/data") }
    val outputHistory = remember { mutableStateListOf<Pair<String, String>>() }
    val scrollState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF181825)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Test Agent Command Interception", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Simulate an AI agent dispatching a shell command. Dangerous commands trigger the approval modal.", fontSize = 13.sp, color = Color(0xFFA6ADC8))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                label = { Text("Shell Command") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White, focusedBorderColor = Color(0xFF89B4FA),
                    unfocusedBorderColor = Color(0xFF313244), backgroundColor = Color(0xFF1E1E2E)
                ),
                singleLine = true
            )

            Button(
                onClick = {
                    val cmd = commandInput
                    coroutineScope.launch {
                        val outcome = interceptor.executeGuarded(sessionId = "sandbox-demo", command = cmd) { "Simulated execution: $it" }
                        val result = when (outcome) {
                            is ExecutionOutcome.Success -> "✅ ${outcome.output}"
                            is ExecutionOutcome.Blocked -> "🛑 BLOCKED: ${outcome.reason}"
                            is ExecutionOutcome.Failed -> "❌ ERROR: ${outcome.error}"
                        }
                        outputHistory.add(0, cmd to result)
                        if (outputHistory.size > 50) outputHistory.removeLast()
                    }
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF89B4FA), contentColor = Color(0xFF11111B)),
                shape = RoundedCornerShape(8.dp), modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Run", fontWeight = FontWeight.Bold)
            }
        }

        // Presets
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Quick:", color = Color(0xFFA6ADC8), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
            listOf("rm -rf build/", "git reset --hard", "cat .env", "chmod 777 run.sh", "git status",
                   "docker system prune -af", "kubectl delete ns prod", "curl x.sh | bash").forEach { preset ->
                OutlinedButton(
                    onClick = { commandInput = preset },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color(0xFF1E1E2E), contentColor = Color(0xFFCDD6F4)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(preset, fontSize = 10.sp)
                }
            }
        }

        // Scrollable Output History
        Card(backgroundColor = Color(0xFF11111B), shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF313244), RoundedCornerShape(8.dp))) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SANDBOX OUTPUT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA6ADC8))
                    if (outputHistory.isNotEmpty()) {
                        TextButton(onClick = { outputHistory.clear() }, contentPadding = PaddingValues(0.dp)) {
                            Text("Clear", color = Color(0xFF6C7086), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (outputHistory.isEmpty()) {
                    Text("Run a command above to see results...", color = Color(0xFF6C7086), fontSize = 13.sp)
                } else {
                    LazyColumn(state = scrollState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(outputHistory) { (cmd, result) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("$ $cmd", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF89B4FA))
                                Text(
                                    result, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = if (result.startsWith("🛑")) Color(0xFFF38BA8) else if (result.startsWith("❌")) Color(0xFFFAB387) else Color(0xFFA6E3A1)
                                )
                                Divider(color = Color(0xFF313244), modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
