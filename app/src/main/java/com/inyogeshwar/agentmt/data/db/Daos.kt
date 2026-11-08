package com.inyogeshwar.agentmt.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SessionRow(
    val id: String,
    val projectId: String,
    val title: String,
    val providerId: String?,
    val modelId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val projectName: String,
    val projectPath: String,
    val kind: String = "chat",
    val status: String = "active",
    val instructions: String = "",
    val todosJson: String = "",
    val profileId: String? = null,
    val skills: String = "",
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects") suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE path = :path LIMIT 1")
    suspend fun byPath(path: String): ProjectEntity?

    @Upsert suspend fun upsert(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface SessionDao {
    @Query(
        """SELECT s.id, s.projectId, s.title, s.providerId, s.modelId, s.createdAt, s.updatedAt, s.pinned, s.kind, s.status, s.instructions, s.todosJson, s.profileId, s.skills,
           p.name AS projectName, p.path AS projectPath
           FROM sessions s JOIN projects p ON p.id = s.projectId
           ORDER BY s.pinned DESC, s.updatedAt DESC"""
    )
    fun observeAll(): Flow<List<SessionRow>>

    @Query(
        """SELECT s.id, s.projectId, s.title, s.providerId, s.modelId, s.createdAt, s.updatedAt, s.pinned, s.kind, s.status, s.instructions, s.todosJson, s.profileId, s.skills,
           p.name AS projectName, p.path AS projectPath
           FROM sessions s JOIN projects p ON p.id = s.projectId WHERE s.id = :id"""
    )
    fun observe(id: String): Flow<SessionRow?>

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: String): SessionEntity?

    @Upsert suspend fun upsert(s: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE sessions SET updatedAt = :now WHERE id = :id") suspend fun touch(id: String, now: Long)

    @Query("UPDATE sessions SET providerId = :providerId, modelId = :modelId WHERE id = :id")
    suspend fun setModel(id: String, providerId: String, modelId: String)

    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean)
    @Query("UPDATE sessions SET providerId = :providerId, modelId = :modelId, profileId = NULL WHERE id = :id") suspend fun pinModel(id: String, providerId: String, modelId: String)
    @Query("UPDATE sessions SET profileId = :profileId WHERE id = :id") suspend fun setProfile(id: String, profileId: String?)
    @Query("UPDATE sessions SET status = :status, updatedAt = :now WHERE id = :id") suspend fun setStatus(id: String, status: String, now: Long)
    @Query("UPDATE sessions SET instructions = :text WHERE id = :id") suspend fun setInstructions(id: String, text: String)
    @Query("UPDATE sessions SET todosJson = :json WHERE id = :id") suspend fun setTodos(id: String, json: String)
    @Query("UPDATE sessions SET skills = :skills WHERE id = :id") suspend fun setSkills(id: String, skills: String)
    @Query("SELECT * FROM sessions") suspend fun all(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id")
    fun observe(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id")
    suspend fun list(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE runId = :runId ORDER BY id")
    fun observeRun(runId: String): Flow<List<MessageEntity>>

    @Insert suspend fun insert(m: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId") suspend fun clear(sessionId: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id >= :fromId")
    suspend fun deleteFrom(sessionId: String, fromId: Long)
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT 200") fun observeAll(): Flow<List<RunEntity>>
    @Query("SELECT * FROM runs WHERE id = :id") fun observe(id: String): Flow<RunEntity?>
    @Query("SELECT * FROM runs WHERE id = :id") suspend fun get(id: String): RunEntity?
    @Upsert suspend fun upsert(r: RunEntity)
    @Query("UPDATE runs SET status = 'cancelled', endedAt = :now WHERE status IN ('running','waiting')")
    suspend fun cancelOrphans(now: Long)
    @Query("DELETE FROM runs WHERE status NOT IN ('running','waiting')") suspend fun clearFinished()
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY sortOrder, name") fun observeAll(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM providers ORDER BY sortOrder, name") suspend fun list(): List<ProviderEntity>
    @Query("SELECT * FROM providers WHERE id = :id") suspend fun get(id: String): ProviderEntity?
    @Query("UPDATE providers SET modelMeta = :meta WHERE id = :id") suspend fun setMeta(id: String, meta: String)
    @Upsert suspend fun upsert(p: ProviderEntity)
    @Query("DELETE FROM providers WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY sortOrder, id") fun observeAll(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules ORDER BY sortOrder, id") suspend fun list(): List<RuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(r: RuleEntity): Long
    @Delete suspend fun delete(r: RuleEntity)
    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM rules") suspend fun maxOrder(): Int
}

@Dao
interface McpDao {
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt") fun observeAll(): Flow<List<McpServerEntity>>
    @Query("SELECT * FROM mcp_servers WHERE enabled = 1") suspend fun enabled(): List<McpServerEntity>
    @Query("SELECT * FROM mcp_servers") suspend fun all(): List<McpServerEntity>
    @Query("SELECT * FROM mcp_servers WHERE pluginId = :pluginId") suspend fun forPlugin(pluginId: String): List<McpServerEntity>
    @Query("DELETE FROM mcp_servers WHERE pluginId = :pluginId") suspend fun deleteForPlugin(pluginId: String)
    @Query("SELECT * FROM mcp_servers WHERE id = :id") suspend fun get(id: String): McpServerEntity?
    @Upsert suspend fun upsert(s: McpServerEntity)
    @Query("DELETE FROM mcp_servers WHERE id = :id") suspend fun delete(id: String)
}
