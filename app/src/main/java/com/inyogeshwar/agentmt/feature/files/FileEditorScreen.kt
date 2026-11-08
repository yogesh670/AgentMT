package com.inyogeshwar.agentmt.feature.files

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.inyogeshwar.agentmt.agent.tools.Workspace
import com.inyogeshwar.agentmt.agent.tools.isBinary
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.app.theme.Mono
import com.inyogeshwar.agentmt.core.ui.AppScaffold
import com.inyogeshwar.agentmt.core.ui.ConfirmDialog
import com.inyogeshwar.agentmt.core.ui.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_EDIT = 1_000_000L

@Composable
fun FileEditorScreen(c: AppContainer, projectId: String, path: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var file by remember { mutableStateOf<File?>(null) }
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = original != null && text != original
    val ext = path.substringAfterLast('.', "").lowercase()
    val previewable = ext in setOf("html", "htm", "md", "markdown", "svg")

    LaunchedEffect(projectId, path) {
        val p = c.db.projects().get(projectId) ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val f = Workspace(File(p.path)).resolve(path); file = f
                when {
                    !f.exists() -> problem = "File not found."
                    f.length() > MAX_EDIT -> problem = "This file is too large to edit on the phone (${f.length() / 1024} KB)."
                    isBinary(f) && ext !in setOf("svg") -> problem = "This looks like a binary file and can't be edited as text."
                    else -> { val t = f.readText(); original = t; text = t }
                }
            }.onFailure { problem = it.message }
        }
    }
    BackHandler(dirty) { confirmLeave = true }

    AppScaffold(path.substringAfterLast('/'), onBack = { if (dirty) confirmLeave = true else onBack() }, actions = {
        IconButton(enabled = dirty, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { file?.writeText(text) }
                original = text; snack.showSnackbar("Saved")
            }
        }) { Icon(Icons.Outlined.Save, "Save", tint = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                problem != null -> Text(problem!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                original == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                else -> Column(Modifier.fillMaxSize()) {
                    if (previewable) TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Code") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Preview") })
                    }
                    if (tab == 0 || !previewable) CodeEditor(text, { text = it }, Modifier.weight(1f).imePadding())
                    else if (ext.startsWith("md") || ext == "markdown") Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { Markdown(text) }
                    else HtmlPreview(file!!, text, Modifier.weight(1f))
                }
            }
            SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
    }
    if (confirmLeave) ConfirmDialog("Discard changes?", "You have unsaved edits in ${path.substringAfterLast('/')}.", "Discard", true,
        onConfirm = onBack, onDismiss = { confirmLeave = false })
}

@Composable
private fun CodeEditor(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val extra = LocalExtra.current
    val lines = remember(value) { value.count { it == '\n' } + 1 }
    val v = rememberScrollState()
    val style = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = extra.codeText)
    Row(modifier.fillMaxWidth().background(extra.code).verticalScroll(v)) {
        Text((1..lines).joinToString("\n"), style = style.copy(color = extra.subtle), modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 12.dp))
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            BasicTextField(value, onChange, textStyle = style, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(top = 12.dp, end = 24.dp, bottom = 200.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(file: File, text: String, modifier: Modifier) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
        }
    }, update = { wv -> wv.loadDataWithBaseURL("file://${file.parent}/", text, "text/html", "utf-8", null) }, modifier = modifier)
}
