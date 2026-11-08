package com.inyogeshwar.agentmt.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Marks where older steps were summarized. Tap to read the summary the model now works from. */
@Composable
fun CompactedRow(summary: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Icon(Icons.Outlined.Compress, null, Modifier.padding(horizontal = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Context compacted · ${if (open) "hide" else "show"} summary", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp)); HorizontalDivider(Modifier.weight(1f))
        }
        if (open) Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
