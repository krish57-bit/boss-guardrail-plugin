package ai.boss.guardrail.ui

import ai.boss.guardrail.interceptor.ActiveApprovalRequest
import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.boss.guardrail.model.ApprovalDecision
import ai.boss.guardrail.model.RiskLevel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun GuardrailApprovalSheet(
    interceptor: GuardrailInterceptor,
    modifier: Modifier = Modifier
) {
    val activeRequest by interceptor.pendingApproval.collectAsState()

    AnimatedVisibility(
        visible = activeRequest != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        activeRequest?.let { request ->
            ApprovalDialogContent(
                request = request,
                onDecision = { decision ->
                    interceptor.submitDecision(request.id, decision)
                }
            )
        }
    }
}

@Composable
private fun ApprovalDialogContent(
    request: ActiveApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit
) {
    var secondsRemaining by remember(request.id) { mutableStateOf(60) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(request.id) {
        while (secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining--
        }
    }

    // Auto-focus for keyboard shortcuts
    LaunchedEffect(request.id) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter -> { onDecision(ApprovalDecision.ALLOW_ONCE); true }
                        Key.Escape -> { onDecision(ApprovalDecision.DENY); true }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            backgroundColor = Color(0xFF1E1E2E),
            elevation = 16.dp,
            modifier = Modifier
                .width(640.dp)
                .padding(24.dp)
                .border(
                    width = 2.dp,
                    color = if (request.evaluation.riskLevel == RiskLevel.CRITICAL_APPROVAL_REQUIRED)
                        Color(0xFFF38BA8) else Color(0xFFFAB387),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Risk Warning",
                            tint = Color(0xFFF38BA8),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Agent Action Intercepted",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (secondsRemaining <= 10) Color(0xFFF38BA8).copy(alpha = 0.3f) else Color(0xFF313244))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Auto-deny in ${secondsRemaining}s",
                            color = if (secondsRemaining <= 10) Color(0xFFF38BA8) else Color(0xFFCDD6F4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Progress bar
                LinearProgressIndicator(
                    progress = secondsRemaining / 60f,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = when {
                        secondsRemaining <= 10 -> Color(0xFFF38BA8)
                        secondsRemaining <= 30 -> Color(0xFFFAB387)
                        else -> Color(0xFFA6E3A1)
                    },
                    backgroundColor = Color(0xFF313244)
                )

                Divider(color = Color(0xFF45475A))

                // Triggered Rules
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "TRIGGERED SECURITY POLICIES",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFFA6ADC8), letterSpacing = 1.sp
                    )
                    request.evaluation.triggeredRules.forEach { rule ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFF38BA8).copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(rule.category.displayName, color = Color(0xFFF38BA8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(rule.explanation, color = Color(0xFFBAC2DE), fontSize = 13.sp)
                        }
                    }
                }

                // Command Preview
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("COMMAND TO EXECUTE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFA6ADC8), letterSpacing = 1.sp)
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF11111B))
                            .border(1.dp, Color(0xFF313244), RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = request.command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = Color(0xFFA6E3A1),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Action Buttons + Keyboard Shortcuts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Keyboard hint
                    Text("Esc = Deny  ·  Enter = Allow", fontSize = 10.sp, color = Color(0xFF6C7086))

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = { onDecision(ApprovalDecision.DENY) },
                        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent, contentColor = Color(0xFFF38BA8)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Deny")
                    }

                    Button(
                        onClick = { onDecision(ApprovalDecision.ALLOW_FOR_SESSION) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF89B4FA), contentColor = Color(0xFF11111B)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Session Allow", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onDecision(ApprovalDecision.ALLOW_ONCE) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFA6E3A1), contentColor = Color(0xFF11111B)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Allow Once", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
