package com.omnix.assistant.agent.memory.manager

import com.omnix.assistant.agent.memory.dao.FactDao
import com.omnix.assistant.agent.memory.dao.MemoryDao
import com.omnix.assistant.agent.memory.dao.PreferenceDao
import com.omnix.assistant.agent.memory.dao.ProcedureDao
import com.omnix.assistant.agent.memory.entity.FactEntity
import com.omnix.assistant.agent.memory.entity.MemoryEntity
import com.omnix.assistant.agent.memory.entity.PreferenceEntity
import com.omnix.assistant.agent.memory.entity.ProcedureEntity
import com.omnix.assistant.agent.memory.extractor.AutonomousMemoryExtractor
import com.omnix.assistant.agent.memory.semantic.SemanticTextMatcher
import com.omnix.assistant.agent.memory.WorkingMemory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Семантика забывания (браузер памяти, блок 4 пересборки фронта):
 * «забудь всё» чистит ВСЕ четыре таблицы (включая процедуры), точечное
 * забывание уносит структурированных близнецов по ключу. Регрессия к
 * найденному разрыву: полная очистка оставляла факты/предпочтения, и
 * браузер памяти показывал «помню» после «забудь всё»; процедуры —
 * записанные сценарии WorkflowExecutor'а — не чистились вообще никак.
 *
 * Прямой suspend-вызов в runTest детерминирован даже с withContext(IO):
 * корутина — ребёнок тестового скоупа, runTest ждёт её завершения.
 */
class OmniMemoryManagerForgetTest {

    private fun manager(
        memoryDao: MemoryDao,
        factDao: FactDao,
        preferenceDao: PreferenceDao,
        procedureDao: ProcedureDao
    ) = OmniMemoryManager(
        workingMemory = mockk(relaxed = true),
        memoryDao = memoryDao,
        factDao = factDao,
        preferenceDao = preferenceDao,
        procedureDao = procedureDao,
        featureEngine = SemanticTextMatcher(),
        memoryExtractor = mockk(relaxed = true)
    )

    @Test
    fun `full wipe clears memories, facts, preferences and procedures`() = runTest {
        val memoryDao = mockk<MemoryDao>(relaxed = true)
        val factDao = mockk<FactDao>(relaxed = true)
        val preferenceDao = mockk<PreferenceDao>(relaxed = true)
        val procedureDao = mockk<ProcedureDao>(relaxed = true)
        coEvery { memoryDao.getAllMemoriesForVectorSearch() } returns listOf(
            MemoryEntity(id = 1L, type = "FACT", content = "Пользователя зовут Александр", keyName = "user.name")
        )
        coEvery { factDao.getAllFacts() } returns listOf(
            FactEntity(factKey = "user.name", factValue = "Александр")
        )
        coEvery { preferenceDao.getAllPreferences() } returns listOf(
            PreferenceEntity(prefKey = "sleep.time", prefValue = "23:00")
        )
        coEvery { procedureDao.getAllProcedures() } returns listOf(
            ProcedureEntity(triggerPhrase = "сон")
        )

        val result = manager(memoryDao, factDao, preferenceDao, procedureDao).forgetMemory("всё")

        assertTrue(result.isSuccess)
        assertEquals(1, result.deletedCount)
        coVerify(exactly = 1) { memoryDao.deleteMemoryById(1L) }
        coVerify(exactly = 1) { factDao.deleteFact("user.name") }
        coVerify(exactly = 1) { preferenceDao.deletePreference("sleep.time") }
        coVerify(exactly = 1) { procedureDao.deleteProcedure("сон") }
    }

    @Test
    fun `targeted forget keeps unrelated memories and cleans twins by key`() = runTest {
        val memoryDao = mockk<MemoryDao>(relaxed = true)
        val factDao = mockk<FactDao>(relaxed = true)
        val preferenceDao = mockk<PreferenceDao>(relaxed = true)
        val procedureDao = mockk<ProcedureDao>(relaxed = true)
        coEvery { memoryDao.getAllMemoriesForVectorSearch() } returns listOf(
            MemoryEntity(id = 1L, type = "FACT", content = "Пользователя зовут Александр", keyName = "user.name"),
            MemoryEntity(id = 2L, type = "EPISODIC", content = "Встреча в четверг")
        )

        val result = manager(memoryDao, factDao, preferenceDao, procedureDao).forgetMemory("александр")

        assertTrue(result.isSuccess)
        assertEquals(1, result.deletedCount)
        coVerify(exactly = 1) { memoryDao.deleteMemoryById(1L) }
        coVerify(exactly = 0) { memoryDao.deleteMemoryById(2L) }
        coVerify(exactly = 1) { factDao.deleteFact("user.name") }
        coVerify(exactly = 1) { preferenceDao.deletePreference("user.name") }
        // Точечное забывание не трогает процедуры: у них нет ключевой связи
        // с воспоминаниями (матчинг — отдельная семантика, вне этого PR).
        coVerify(exactly = 0) { procedureDao.deleteProcedure(any()) }
    }
}
