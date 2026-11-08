package com.inyogeshwar.agentmt.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.inyogeshwar.agentmt.BuildConfig
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.app.theme.LocalExtra
import com.inyogeshwar.agentmt.core.termux.TermuxBridge
import com.inyogeshwar.agentmt.data.prefs.ApprovalMode

/** One phone permission AgentMT can use, with how to check it and how to ask for it. */
internal data class PhonePerm(val id: String, val title: String, val why: String, val granted: Boolean)

internal object PhonePerms {
    fun list(ctx: Context): List<PhonePerm> = buildList {
        add(PhonePerm("files", "All files access", "Open and edit project folders anywhere in storage.",
            if (Build.VERSION.SDK_INT >= 30) runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED))
        if (Build.VERSION.SDK_INT >= 33) add(PhonePerm("notify", "Notifications", "See progress and get told when a task finishes.",
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
        if (BuildConfig.FULL) {
            add(PhonePerm("battery", "Run in background", "Keep long tasks and builds alive with the screen off.",
                runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)))
            if (Build.VERSION.SDK_INT >= 26) add(PhonePerm("install", "Install built apps", "Open Android's installer for APKs the agent builds.",
                runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)))
            if (TermuxBridge.installed(ctx)) add(PhonePerm("termux", "Use my Termux", "Run commands with the packages you installed in Termux.",
                TermuxBridge.permitted(ctx)))
        }
    }
}

@Composable
internal fun PermissionsStep(c: AppContainer, next: () -> Unit) {
    val ctx = LocalContext.current
    val extra = LocalExtra.current
    val settings by c.settings.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val perms = remember(tick) { PhonePerms.list(ctx) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (granted && perms.any { it.id == "termux" }) c.settings.update { it.copy(useTermux = true) }
    }
    fun open(intent: Intent, fallback: Intent? = null) {
        runCatching { ctx.startActivity(intent) }.onFailure { fallback?.let { f -> runCatching { ctx.startActivity(f) } } }
    }
    val pkgUri = Uri.parse("package:" + ctx.packageName)
    fun ask(id: String) = when (id) {
        "files" -> if (Build.VERSION.SDK_INT >= 30) open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) else runtime.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        "notify" -> runtime.launch(Manifest.permission.POST_NOTIFICATIONS)
        "battery" -> @Suppress("BatteryLife") open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        "install" -> open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri))
        "termux" -> runtime.launch(TermuxBridge.PERMISSION)
        else -> Unit
    }

    Text("Permissions", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text("Set this once so the agent doesn't stop mid-task to ask. You can change everything later in Settings.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Spacer(Modifier.height(18.dp))
    Text("While coding, the agent should…", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    ApprovalMode.entries.forEach { m ->
        val sel = settings.approvalMode == m
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .border(if (sel) 1.5.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable { c.settings.update { it.copy(approvalMode = m) } }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(sel, onClick = { c.settings.update { it.copy(approvalMode = m) } })
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.bodyLarge)
                Text(m.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Text("Phone access", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    perms.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.bodyLarge)
                Text(p.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            if (p.granted) Icon(Icons.Filled.CheckCircle, "Allowed", tint = extra.success, modifier = Modifier.size(26.dp))
            else FilledTonalButton(onClick = { ask(p.id) }) { Text("Allow") }
        }
    }
    if (perms.any { it.id == "termux" }) Text("Termux also needs a one-time setup: Menu → Linux, build & tools → Termux.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

    Spacer(Modifier.height(24.dp))
    val left = perms.count { !it.granted }
    Button(onClick = next, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
        Text(if (left == 0) "Continue" else "Continue ($left not allowed)")
    }
}
