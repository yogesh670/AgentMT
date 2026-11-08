package com.inyogeshwar.agentmt

import android.app.Application
import com.inyogeshwar.agentmt.app.AppContainer
import com.inyogeshwar.agentmt.core.CrashReporter

class AgentMTApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.inyogeshwar.agentmt.agent.ProviderCatalog.init(this)
        com.inyogeshwar.agentmt.core.termux.TermuxBridge.init(this)
        CrashReporter.install(this)
        container = AppContainer(this)
    }
}
