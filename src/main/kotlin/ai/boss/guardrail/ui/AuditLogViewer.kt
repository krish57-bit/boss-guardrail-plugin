package ai.boss.guardrail.ui

import ai.boss.guardrail.interceptor.AuditLogEntry
import ai.boss.guardrail.interceptor.GuardrailInterceptor
import ai.boss.guardrail.model.ApprovalDecision
import ai.boss.guardrail.model.ExecutionOutcome
import ai.boss.guardrail.model.RiskLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AuditLogViewer(
    interceptor: GuardrailInterceptor,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val logs by interceptor.auditLogs.collectAsState()

    val filteredLogs = remember(logs, searchQuery, selectedFilter) {
        logs.reversed().filter { entry ->
            val matchesQuery = searchQuery.isBlank() ||
                entry.command.contains(searchQuery, ignoreCase = true) ||
                entry.sessionId.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "BLOCKED" -> entry.outcome is ExecutionOutcome.Blocked
                "ALLOWED" -> entry.outcome is ExecutionOutcome.Success
                "CRITICAL" -> entry.riskLevel == RiskLevel.CRITICAL_APPROVAL_REQUIRED
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF181825)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search commands or sessions...", color = Color(0xFF6C7086)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFA6ADC8)) },
                modifier = Modifier.width(340.dp),
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White,
                    focusedBorderColor = Color(0xFF89B4FA),
                    unfocusedBorderColor = Color(0xFF313244),
                    backgroundColor = Color(0xFF1E1E2E)
                ),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("ALL", "BLOCKED", "ALLOWED", "CRITICAL").forEach { filter ->
                    val selected = selectedFilter == filter
                    TextButton(
                        onClick = { selectedFilter = filter },
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF89B4FA) else Color(0xFF1E1E2E))
                            .border(1.dp, if (selected) Color(0xFF89B4FA) else Color(0xFF313244), RoundedCornerShape(8.dp)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(filter, color = if (selected) Color(0xFF11111B) else Color(0xFFBAC2DE), fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }

                IconButton(onClick = { interceptor.clearAuditLogs() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = Color(0xFF6C7086))
                }
            }
        }

        // Header
        Card(backgroundColor = Color(0xFF1E1E2E), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()) {
                Text("TIME", color = Color(0xFFA6ADC8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("SESSION", color = Color(0xFFA6ADC8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                Text("RISK", color = Color(0xFFA6ADC8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("COMMAND", color = Color(0xFFA6ADC8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(3f))
                Text("OUTCOME", color = Color(0xFFA6ADC8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
            }
        }

        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No audit logs yet. Run a command in the sandbox to see results here.", color = Color(0xFF6C7086), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filteredLogs, key = { it.id }) { entry ->
                    AuditLogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun AuditLogRow(entry: AuditLogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(backgroundColor = Color(0xFF1E1E2E), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(timeFormat.format(Date(entry.timestampMs)), color = Color(0xFFCDD6F4), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(entry.sessionId.take(12), color = Color(0xFFA6ADC8), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.2f))
            Box(modifier = Modifier.weight(1f)) { RiskBadge(entry.riskLevel) }
            Text(entry.command, color = Color(0xFFF5E0DC), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(3f))
            Box(modifier = Modifier.weight(1.2f)) { OutcomeBadge(entry.outcome, entry.decision) }
        }
    }
}

@Composable
internal fun RiskBadge(risk: RiskLevel) {
    val (bgColor, textColor, label) = when (risk) {
        RiskLevel.SAFE -> Triple(Color(0xFFA6E3A1).copy(alpha = 0.15f), Color(0xFFA6E3A1), "SAFE")
        RiskLevel.WARNING -> Triple(Color(0xFFFAB387).copy(alpha = 0.15f), Color(0xFFFAB387), "WARN")
        RiskLevel.CRITICAL_APPROVAL_REQUIRED -> Triple(Color(0xFFF38BA8).copy(alpha = 0.2f), Color(0xFFF38BA8), "CRITICAL")
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bgColor).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OutcomeBadge(outcome: ExecutionOutcome, decision: ApprovalDecision?) {
    val (bgColor, textColor, label) = when (outcome) {
        is ExecutionOutcome.Success -> if (decision == ApprovalDecision.ALLOW_FOR_SESSION) Triple(Color(0xFF89B4FA).copy(0.2f), Color(0xFF89B4FA), "SESSION") else Triple(Color(0xFFA6E3A1).copy(0.2f), Color(0xFFA6E3A1), "EXECUTED")
        is ExecutionOutcome.Blocked -> Triple(Color(0xFFF38BA8).copy(0.2f), Color(0xFFF38BA8), "BLOCKED")
        is ExecutionOutcome.Failed -> Triple(Color(0xFFFAB387).copy(0.2f), Color(0xFFFAB387), "ERROR")
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bgColor).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
