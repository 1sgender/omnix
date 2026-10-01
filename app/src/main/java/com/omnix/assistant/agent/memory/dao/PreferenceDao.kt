package com.omnix.assistant.agent.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnix.assistant.agent.memory.entity.PreferenceEntity

@Dao
interface PreferenceDao {

    @Query("SELECT * FROM preferences ORDER BY updated_at DESC")
    suspend fun getAllPreferences(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreference(pref: PreferenceEntity): Long

    @Query("DELETE FROM preferences WHERE pref_key = :key")
    suspend fun deletePreference(key: String)
}
