package com.inyogeshwar.agentmt.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY providerId, priority, createdAt") fun observeAll(): Flow<List<CredentialEntity>>
    @Query("SELECT * FROM credentials WHERE providerId = :providerId ORDER BY priority, createdAt") fun observeFor(providerId: String): Flow<List<CredentialEntity>>
    @Query("SELECT * FROM credentials WHERE providerId = :providerId ORDER BY priority, createdAt") suspend fun listFor(providerId: String): List<CredentialEntity>
    @Query("SELECT * FROM credentials") suspend fun all(): List<CredentialEntity>
    @Query("SELECT * FROM credentials WHERE id = :id") suspend fun get(id: String): CredentialEntity?
    @Upsert suspend fun upsert(c: CredentialEntity)
    @Query("DELETE FROM credentials WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM credentials WHERE providerId = :providerId") suspend fun deleteFor(providerId: String)
    @Query("SELECT COALESCE(MAX(priority), -1) FROM credentials WHERE providerId = :providerId") suspend fun maxPriority(providerId: String): Int
}

@Dao
interface RoutingDao {
    @Query("SELECT * FROM routing_profiles ORDER BY createdAt") fun observeAll(): Flow<List<RoutingProfileEntity>>
    @Query("SELECT * FROM routing_profiles WHERE id = :id") suspend fun get(id: String): RoutingProfileEntity?
    @Upsert suspend fun upsert(p: RoutingProfileEntity)
    @Query("DELETE FROM routing_profiles WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage WHERE day >= :fromDay ORDER BY day DESC") fun observeSince(fromDay: String): Flow<List<UsageEntity>>
    @Query("SELECT * FROM usage WHERE day = :day AND credentialId = :cred AND model = :model") suspend fun get(day: String, cred: String, model: String): UsageEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(u: UsageEntity)

    @Transaction
    suspend fun add(day: String, cred: String, model: String, providerId: String, ok: Boolean, inTok: Long, outTok: Long) {
        val cur = get(day, cred, model) ?: UsageEntity(day, cred, model, providerId)
        put(cur.copy(requests = cur.requests + 1, failures = cur.failures + if (ok) 0 else 1,
            inputTokens = cur.inputTokens + inTok, outputTokens = cur.outputTokens + outTok))
    }
}

data class TagCount(val name: String, val color: Int, val uses: Int)

@Dao
interface TagDao {
    @Query("SELECT t.name, t.color, (SELECT COUNT(*) FROM tag_links l WHERE l.tag = t.name) AS uses FROM tags t ORDER BY uses DESC, t.name")
    fun observeCounts(): Flow<List<TagCount>>
    @Query("SELECT * FROM tags ORDER BY name") suspend fun all(): List<TagEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(t: TagEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun link(l: TagLinkEntity)
    @Delete suspend fun unlink(l: TagLinkEntity)
    @Query("SELECT * FROM tag_links") fun observeLinks(): Flow<List<TagLinkEntity>>
    @Query("SELECT tag FROM tag_links WHERE kind = :kind AND ref = :ref ORDER BY tag") fun observeFor(kind: String, ref: String): Flow<List<String>>
    @Query("DELETE FROM tag_links WHERE tag = :tag") suspend fun clearLinks(tag: String)
    @Query("DELETE FROM tags WHERE name = :tag") suspend fun deleteTag(tag: String)
    @Query("UPDATE tag_links SET ref = :newRef WHERE kind = :kind AND ref = :oldRef") suspend fun moveRef(kind: String, oldRef: String, newRef: String)
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY name") fun observeAll(): Flow<List<PluginEntity>>
    @Query("SELECT * FROM plugins") suspend fun all(): List<PluginEntity>
    @Query("SELECT * FROM plugins WHERE id = :id") suspend fun get(id: String): PluginEntity?
    @Upsert suspend fun upsert(p: PluginEntity)
    @Query("DELETE FROM plugins WHERE id = :id") suspend fun delete(id: String)
}
