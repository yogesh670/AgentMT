package com.inyogeshwar.agentmt.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import kotlinx.coroutines.delay

/** A looping, real-looking mini transcript that shows what the agent does — no stock illustration. */
@Composable
fun AgentDemo(modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            step = 0; delay(700)
            for (i in 1..6) { step = i; delay(if (i == 5) 1500 else 800) }
            delay(2600)
        }
    }
    val extra = LocalExtra.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).padding(14.dp).heightIn(min = 250.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(extra.danger, extra.warning, extra.success).forEach { Box(Modifier.padding(end = 5.dp).size(8.dp).clip(CircleShape).background(it.copy(alpha = 0.7f))) }
            Spacer(Modifier.width(6.dp))
            Text("~/projects/todo-app", fontFamily = Mono, fontSize = 11.sp, color = extra.subtle)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Add dark mode to the app", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(extra.userBubble).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Line(step >= 1, Icons.Outlined.Search, "grep “theme” **/*.css")
        Line(step >= 2, Icons.Outlined.Description, "read styles.css")
        Line(step >= 3, Icons.Outlined.EditNote, "edit styles.css  +18 −2")
        AnimatedVisibility(step >= 4, enter = fadeIn() + expandVertically()) {
            Text(
                "+ @media (prefers-color-scheme: dark) {\n+   :root { --bg: #0f1115; --fg: #e8eaed; }\n+ }",
                fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp, color = extra.success,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(extra.code).padding(10.dp),
            )
        }
        AnimatedVisibility(step >= 6, enter = fadeIn()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(15.dp), tint = extra.success)
                Spacer(Modifier.width(6.dp))
                Text("Done. Dark mode follows the system setting.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Line(visible: Boolean, icon: ImageVector, text: String) {
    AnimatedVisibility(visible, enter = fadeIn() + expandVertically()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(text, fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
