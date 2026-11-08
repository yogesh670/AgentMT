package com.inyogeshwar.agentmt

import android.content.Context

object BuildConfigShim {
    fun versionName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""
}
