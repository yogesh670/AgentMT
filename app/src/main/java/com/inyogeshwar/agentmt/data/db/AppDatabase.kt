package com.inyogeshwar.agentmt.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class, SessionEntity::class, MessageEntity::class, RunEntity::class,
        ProviderEntity::class, RuleEntity::class, McpServerEntity::class,
        CredentialEntity::class, RoutingProfileEntity::class, UsageEntity::class,
        TagEntity::class, TagLinkEntity::class, PluginEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun runs(): RunDao
    abstract fun providers(): ProviderDao
    abstract fun rules(): RuleDao
    abstract fun mcp(): McpDao
    abstract fun credentials(): CredentialDao
    abstract fun routing(): RoutingDao
    abstract fun usage(): UsageDao
    abstract fun tags(): TagDao
    abstract fun plugins(): PluginDao

    companion object {
        /** JVM tests (Robolectric on desktop) name a bundled SQLite driver class here; the app uses the platform SQLite. */
        private val testDriver: androidx.sqlite.SQLiteDriver? by lazy {
            System.getProperty("agentmt.sqliteDriver")?.let { Class.forName(it).getDeclaredConstructor().newInstance() as androidx.sqlite.SQLiteDriver }
        }

        fun build(context: Context, name: String = "agentmt.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .apply { testDriver?.let { setDriver(it) } }
                .build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
            }
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                MIGRATION_1_2_SQL.forEach { sql -> connection.prepare(sql).use { it.step() } }
            }
        }

        val MIGRATION_1_2_SQL = listOf(
            "ALTER TABLE sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'",
            "ALTER TABLE sessions ADD COLUMN status TEXT NOT NULL DEFAULT 'active'",
            "ALTER TABLE sessions ADD COLUMN instructions TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE sessions ADD COLUMN todosJson TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE sessions ADD COLUMN profileId TEXT",
            "ALTER TABLE sessions ADD COLUMN skills TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE messages ADD COLUMN attachmentsJson TEXT",
            "ALTER TABLE messages ADD COLUMN routeNote TEXT",
            "ALTER TABLE runs ADD COLUMN routeLog TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE providers ADD COLUMN modelMeta TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE mcp_servers ADD COLUMN projectId TEXT",
            "ALTER TABLE mcp_servers ADD COLUMN pluginId TEXT",
            "CREATE TABLE IF NOT EXISTS `credentials` (`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, `label` TEXT NOT NULL, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `status` TEXT NOT NULL, `cooldownUntil` INTEGER NOT NULL, `lastError` TEXT, `lastUsedAt` INTEGER NOT NULL, `requests` INTEGER NOT NULL, `failures` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `lastLatencyMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_credentials_providerId` ON `credentials` (`providerId`)",
            "CREATE TABLE IF NOT EXISTS `routing_profiles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `candidatesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `usage` (`day` TEXT NOT NULL, `credentialId` TEXT NOT NULL, `model` TEXT NOT NULL, `providerId` TEXT NOT NULL, `requests` INTEGER NOT NULL, `failures` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, PRIMARY KEY(`day`, `credentialId`, `model`))",
            "CREATE TABLE IF NOT EXISTS `tags` (`name` TEXT NOT NULL, `color` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`name`))",
            "CREATE TABLE IF NOT EXISTS `tag_links` (`tag` TEXT NOT NULL, `kind` TEXT NOT NULL, `ref` TEXT NOT NULL, PRIMARY KEY(`tag`, `kind`, `ref`))",
            "CREATE INDEX IF NOT EXISTS `index_tag_links_ref` ON `tag_links` (`ref`)",
            "CREATE TABLE IF NOT EXISTS `plugins` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `description` TEXT NOT NULL, `source` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `dir` TEXT NOT NULL, `summaryJson` TEXT NOT NULL, `installedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

/** Deletes chats, messages, runs and usage but keeps providers, keys, rules, MCP servers, projects and tags. */
suspend fun AppDatabase.clearAllTablesSafe() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    openHelper.writableDatabase.apply {
        execSQL("DELETE FROM messages"); execSQL("DELETE FROM sessions"); execSQL("DELETE FROM runs")
        execSQL("DELETE FROM tag_links WHERE kind = 'session'")
    }
    invalidationTracker.refreshVersionsAsync()
}
