package com.inyogeshwar.agentmt

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inyogeshwar.agentmt.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Launches the real app on the JVM (Robolectric) to catch start-up and navigation crashes without a phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class AppLaunchTest {

    @get:Rule val compose = createEmptyComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun onboarded() = (ctx as AgentMTApplication).container.settings.update { it.copy(onboarded = true) }
    private fun tap(text: String) {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
            runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[0].performScrollToNode(hasText(text)) }
        val n = compose.onAllNodesWithText(text)[0]
        runCatching { n.performScrollTo() }
        n.performClick(); compose.waitForIdle()
    }
    private fun dump(tag: String) = println("TREE[$tag]\n" + compose.onRoot().printToString(maxDepth = 30))

    @Test fun freshInstallOnboardingFlow() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithText("Get started").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Start in a playground").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            // The step change animates; a Skip tap can land mid-transition, so tap until the tour shows.
            repeat(4) {
                if (compose.onAllNodesWithText("Start building").fetchSemanticsNodes().isEmpty()) {
                    compose.mainClock.advanceTimeBy(1000)
                    compose.onAllNodesWithText("Skip").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let { compose.onAllNodesWithText("Skip")[0].performClick() }
                    compose.waitForIdle()
                }
            }
            if (compose.onAllNodesWithText("Start building").fetchSemanticsNodes().isEmpty()) dump("before-start")
            tap("Start building")
            compose.waitForIdle()
            compose.onNodeWithText("Message or /command").assertExists()
        }
    }

    @Test fun everyMainScreenOpens() {
        onboarded()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            fun openDrawerAnd(label: String) {
                compose.onNodeWithContentDescription("Menu").performClick(); compose.waitForIdle()
                compose.onAllNodesWithText(label)[0].performClick(); compose.waitForIdle()
            }
            openDrawerAnd("Tasks"); compose.onNodeWithText("New task", useUnmergedTree = true).performClick(); compose.waitForIdle()
            compose.onNodeWithText("Build a website").performClick(); compose.waitForIdle()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            openDrawerAnd("Runs"); it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            openDrawerAnd("Tools, skills & plugins")
            listOf("MCP", "Skills", "Plugins", "Built-in").forEach { t -> compose.onNodeWithText(t).performClick(); compose.waitForIdle() }
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            openDrawerAnd("Settings")
            tap("Routing & fallback")
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            tap("Providers & API keys")
            compose.onAllNodesWithText("OpenRouter")[0].performClick(); compose.waitForIdle()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            tap("Backup & restore")
            compose.onNodeWithText("Export workspace").assertExists()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            compose.onNodeWithText("Choose model").performClick(); compose.waitForIdle()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            compose.onNodeWithContentDescription("More").assertExists()
        }
    }

    @Test fun linuxBrowserAndExtensionsOpen() {
        onboarded()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Menu").performClick(); compose.waitForIdle()
            compose.onAllNodesWithText("Linux, build & tools")[0].performClick(); compose.waitForIdle()
            compose.onNodeWithText("Build APKs on this phone").assertExists()
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
            compose.onNodeWithContentDescription("Menu").performClick(); compose.waitForIdle()
            compose.onAllNodesWithText("Browser")[0].performClick(); compose.waitForIdle()
            compose.onNodeWithContentDescription("More").performClick(); compose.waitForIdle()
            compose.onAllNodesWithText("Extensions (0)")[0].performClick(); compose.waitForIdle()
            compose.onNodeWithText("Add extension", useUnmergedTree = true).performClick(); compose.waitForIdle()
            compose.onNodeWithText("Paste code").assertExists()
        }
    }

    /** Regression for v0.5.0: a stop right after a start must still call startForeground (ForegroundServiceDidNotStartInTime). */
    @Test fun serviceStopRightAfterStartGoesForegroundFirst() {
        val stop = android.content.Intent(ctx, com.inyogeshwar.agentmt.agent.AgentService::class.java).setAction("stop")
        val ctl = org.robolectric.Robolectric.buildService(com.inyogeshwar.agentmt.agent.AgentService::class.java, stop).create()
        val svc = ctl.get()
        org.junit.Assert.assertTrue(org.robolectric.Shadows.shadowOf(svc).isLastForegroundNotificationAttached || org.robolectric.Shadows.shadowOf(svc).lastForegroundNotification != null)
        ctl.startCommand(0, 1)
        org.junit.Assert.assertTrue(org.robolectric.Shadows.shadowOf(svc).isStoppedBySelf)
    }

    /** Regression for the v0.4.0 crash: TerminalView NPE when the typeface was set before the text size. */
    @Test fun terminalViewCreates() {
        val v = com.inyogeshwar.agentmt.feature.terminal.newTerminalView(ctx, 36)
        v.setTextSize(48)
    }

    /** Builds a v0.2.0 (schema 1) database, then lets v0.3 open it: Room validates the migrated schema. */
    @Test fun upgradeFromV1Database() {
        val schema = JSONObject(File("schemas/com.inyogeshwar.agentmt.data.db.AppDatabase/1.json").readText()).getJSONObject("database")
        val f = ctx.getDatabasePath("upgrade-test.db"); f.parentFile!!.mkdirs(); f.delete()
        val conn = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(f.path)
        fun exec(sql: String) = conn.prepare(sql).use { it.step() }
        run {
            val ents = schema.getJSONArray("entities")
            for (i in 0 until ents.length()) {
                val e = ents.getJSONObject(i); val t = e.getString("tableName")
                exec(e.getString("createSql").replace("\${TABLE_NAME}", t))
                e.optJSONArray("indices")?.let { ix -> for (k in 0 until ix.length()) exec(ix.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", t)) }
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) exec(setup.getString(i))
            exec("INSERT INTO projects (id, name, path, createdAt, lastOpenedAt) VALUES ('p1','Playground','/tmp/x',1,1)")
            exec("INSERT INTO sessions (id, projectId, title, providerId, modelId, createdAt, updatedAt, pinned) VALUES ('s1','p1','Old chat',NULL,NULL,1,1,0)")
            exec("INSERT INTO messages (sessionId, role, text, createdAt) VALUES ('s1','user','hello',1)")
            exec("PRAGMA user_version = 1")
        }
        conn.close()
        val room = AppDatabase.build(ctx, "upgrade-test.db")
        runBlocking {
            assertEquals("chat", room.sessions().all().single().kind)
            assertEquals(1, room.messages().list("s1").size)
            assertEquals(0, room.credentials().all().size)
        }
        room.close()
    }
}
