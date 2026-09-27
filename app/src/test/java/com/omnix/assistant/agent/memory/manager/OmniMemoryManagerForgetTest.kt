package com.omnix.assistant.agent.memory.manager

import com.omnix.assistant.agent.memory.dao.FactDao
import com.omnix.assistant.agent.memory.dao.MemoryDao
import com.omnix.assistant.agent.memory.dao.PreferenceDao
import com.omnix.assistant.agent.memory.entity.FactEntity
import com.omnix.assistant.agent.memory.entity.MemoryEntity
import com.omnix.assistant.agent.memory.entity.PreferenceEntity
import com.omnix.assistant.agent.memory.extractor.AutonomousMemoryExtractor
import com.omnix.assistant.agent.memory.semantic.SemanticTextMatcher
import com.omnix.assistant.agent.memory.WorkingMemory
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Семантика забывания (браузер памяти, блок 4 пересборки фронта):
 * «забудь всё» чистит ВСЕ три таблицы, точечное забывание уносит
 * структурированных близнецов по ключу. Регрессия к найденному разрыву:
 * полная очистка оставляла факты/предпочтения, и браузер памяти показывал
 * «помню» после «забудь всё».
 *
 * Прямой suspend-вызов в runTest детерминирован даже с withContext(IO):
 * корутина — ребёнок тестового скоупа, runTest ждёт её завершения.
 */
class OmniMemoryManagerForgetTest {

    private fun manager(
        memoryDao: MemoryDao,
        factDao: FactDao,
        preferenceDao: PreferenceDao
    ) = OmniMemoryManager(
        workingMemory = mockk(relaxed = true),
        memoryDao = memoryDao,
        factDao = factDao,
        preferenceDao = preferenceDao,
        featureEngine = SemanticTextMatcher(),
        memoryExtractor = mockk(relaxed = true)
    )

    @Test
    fun `full wipe clears memories, facts and preferences`() = runTest {
        val memoryDao = mockk<MemoryDao>(relaxed = true)
        val factDao = mockk<FactDao>(relaxed = true)
        val preferenceDao = mockk<PreferenceDao>(relaxed = true)
        every { memoryDao.getAllMemoriesForVectorSearch() } returns listOf(
            MemoryEntity(id = 1L, type = "FACT", content = "Пользователя зовут Александр", keyName = "user.name")
        )
        every { factDao.getAllFacts() } returns listOf(
            FactEntity(factKey = "user.name", factValue = "Александр")
        )
        every { preferenceDao.getAllPreferences() } returns listOf(
            PreferenceEntity(prefKey = "sleep.time", prefValue = "23:00")
        )

        val result = manager(memoryDao, factDao, preferenceDao).forgetMemory("всё")

        assertTrue(result.isSuccess)
        assertEquals(1, result.deletedCount)
        coVerify(exactly = 1) { memoryDao.deleteMemoryById(1L) }
        coVerify(exactly = 1) { factDao.deleteFact("user.name") }
        coVerify(exactly = 1) { preferenceDao.deletePreference("sleep.time") }
    }

    @Test
    fun `targeted forget keeps unrelated memories and cleans twins by key`() = runTest {
        val memoryDao = mockk<MemoryDao>(relaxed = true)
        val factDao = mockk<FactDao>(relaxed = true)
        val preferenceDao = mockk<PreferenceDao>(relaxed = true)
        every { memoryDao.getAllMemoriesForVectorSearch() } returns listOf(
            MemoryEntity(id = 1L, type = "FACT", content = "Пользователя зовут Александр", keyName = "user.name"),
            MemoryEntity(id = 2L, type = "EPISODIC", content = "Встреча в четверг")
        )

        val result = manager(memoryDao, factDao, preferenceDao).forgetMemory("александр")

        assertTrue(result.isSuccess)
        assertEquals(1, result.deletedCount)
        coVerify(exactly = 1) { memoryDao.deleteMemoryById(1L) }
        coVerify(exactly = 0) { memoryDao.deleteMemoryById(2L) }
        coVerify(exactly = 1) { factDao.deleteFact("user.name") }
        coVerify(exactly = 1) { preferenceDao.deletePreference("user.name") }
    }
}
