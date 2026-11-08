package com.inyogeshwar.agentmt.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.Card
import com.inyogeshwar.agentmt.core.ui.ListRow

/** Open source notices and full license texts, read from assets/licenses. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files = remember { ctx.assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted() }
    var open by remember { mutableStateOf<String?>(null) }
    val name = open
    val text = remember(name) { runCatching { ctx.assets.open("licenses/" + (name ?: "NOTICES.md")).bufferedReader().use { it.readText() } }.getOrDefault("") }
    AppScaffold(name?.removeSuffix(".txt") ?: "Open source licenses", onBack = { if (open != null) open = null else onBack() }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (name == null) Card { Column { files.forEach { f -> ListRow(f.removeSuffix(".txt") + " full text", null, Icons.Outlined.Description, onClick = { open = f }) } } }
            Text(text, fontFamily = if (name != null) FontFamily.Monospace else FontFamily.Default, fontSize = if (name != null) 11.sp else 13.sp,
                lineHeight = if (name != null) 15.sp else 19.sp)
        }
    }
}
