package com.inyogeshwar.agentmt.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inyogeshwar.agentmt.app.TAG_COLORS
import com.inyogeshwar.agentmt.app.normalizeTag
import com.inyogeshwar.agentmt.app.theme.Mono

fun tagColor(tag: String) = Color(TAG_COLORS[(tag.hashCode() and 0x7fffffff) % TAG_COLORS.size])

@Composable
fun TagChip(tag: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = tagColor(tag)
    Text("#$tag", fontFamily = Mono, fontSize = 11.sp, color = c,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 6.dp, vertical = 2.dp))
}

val SUGGESTED_TAGS = listOf("frontend", "backend", "research", "documentation", "debugging", "design", "important", "video", "android")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(current: List<String>, known: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("New tag") }, placeholder = { Text("#frontend") },
                    trailingIcon = { TextButton(enabled = normalizeTag(input) != null, onClick = { normalizeTag(input)?.let(onAdd); input = "" }) { Text("Add") } })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (current + known + SUGGESTED_TAGS).distinct().filter { input.isBlank() || it.contains(normalizeTag(input) ?: "") }.take(30).forEach { t ->
                        FilterChip(t in current, { if (t in current) onRemove(t) else onAdd(t) }, label = { Text("#$t") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
