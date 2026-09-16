package ai.boss.guardrail.ui

import ai.boss.guardrail.engine.ShellPolicyEngine
import ai.boss.guardrail.model.PolicyRule
import ai.boss.guardrail.model.RiskLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
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

@Composable
fun PolicyRulesViewer(
    engine: ShellPolicyEngine,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }

    val rules = engine.allRules
    val categories = listOf("ALL") + rules.map { it.category.displayName }.distinct()

    val filteredRules = remember(rules, searchQuery, selectedCategory) {
        rules.filter { rule ->
            val matchesSearch = searchQuery.isBlank() ||
                rule.name.contains(searchQuery, ignoreCase = true) ||
                rule.id.contains(searchQuery, ignoreCase = true) ||
                rule.explanation.contains(searchQuery, ignoreCase = true)

            val matchesCategory = selectedCategory == "ALL" || rule.category.displayName == selectedCategory

            matchesSearch && matchesCategory
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF181825)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Active Security Rules", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${filteredRules.size} rules loaded · ${filteredRules.count { it.riskLevel == RiskLevel.CRITICAL_APPROVAL_REQUIRED }} critical · ${filteredRules.count { it.riskLevel == RiskLevel.WARNING }} warnings",
                    fontSize = 12.sp, color = Color(0xFFA6ADC8))
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter rules...", color = Color(0xFF6C7086)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFA6ADC8)) },
                modifier = Modifier.width(280.dp),
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White, focusedBorderColor = Color(0xFF89B4FA),
                    unfocusedBorderColor = Color(0xFF313244), backgroundColor = Color(0xFF1E1E2E)
                ),
                singleLine = true
            )
        }

        // Category filter row (scrollable)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.take(8).forEach { cat ->
                val selected = selectedCategory == cat
                TextButton(
                    onClick = { selectedCategory = cat },
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF89B4FA) else Color(0xFF1E1E2E))
                        .border(1.dp, if (selected) Color(0xFF89B4FA) else Color(0xFF313244), RoundedCornerShape(6.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(cat, color = if (selected) Color(0xFF11111B) else Color(0xFFBAC2DE), fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        // Rule list
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredRules, key = { it.id }) { rule ->
                PolicyRuleCard(rule)
            }
        }
    }
}

@Composable
private fun PolicyRuleCard(rule: PolicyRule) {
    Card(backgroundColor = Color(0xFF1E1E2E), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.category.icon, fontSize = 16.sp)
                    Text(rule.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                RiskBadge(rule.riskLevel)
            }

            Text(rule.explanation, color = Color(0xFFBAC2DE), fontSize = 12.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF313244)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(rule.id, color = Color(0xFF6C7086), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF313244)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(rule.category.displayName, color = Color(0xFF6C7086), fontSize = 10.sp)
                }
            }

            // Pattern preview
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF11111B)).border(1.dp, Color(0xFF313244), RoundedCornerShape(6.dp)).padding(8.dp)
            ) {
                Text(rule.pattern.pattern, color = Color(0xFFFAB387), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
            }
        }
    }
}
