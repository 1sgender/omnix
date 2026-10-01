package com.omnix.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnix.assistant.agent.memory.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY updated_at DESC")
    fun getAllMemoriesStream(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories")
    suspend fun getAllMemoriesForVectorSearch(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("UPDATE memories SET access_count = access_count + 1, last_accessed_at = :timestamp WHERE id = :id")
    suspend fun recordAccess(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memories WHERE key_name = :key")
    suspend fun deleteMemoryByKey(key: String)
}
