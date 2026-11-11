package com.inyogeshwar.agentmt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.inyogeshwar.agentmt.agent.mcp.McpOAuth
import com.inyogeshwar.agentmt.app.AgentMTApp
import com.inyogeshwar.agentmt.app.AppContainer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AgentMTApplication).container
        setContent { AgentMTApp(container) }
        handleOAuthRedirect(intent, container)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent, (application as AgentMTApplication).container)
    }

    private fun handleOAuthRedirect(intent: Intent?, container: AppContainer) {
        val data = intent?.data ?: return
        if (data.scheme == McpOAuth.SCHEME && data.host == McpOAuth.HOST) {
            container.appScope.launch { container.mcpOAuth.handleRedirect(data) }
        }
    }
}
