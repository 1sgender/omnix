package com.omnix.assistant.agent.decision

import com.omnix.assistant.agent.registry.ToolRegistry
import com.omnix.assistant.core.network.NetworkMonitor
import com.omnix.assistant.domain.repository.AIRepository
import com.omnix.assistant.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V012: облачный исполнитель закрыт без подключённой пары OMNIX и говорит
 * почему; без сети — обычное офлайн-сообщение (null), а не «подключите наушники».
 */
class CloudPairAccessTest {

    private val network: NetworkMonitor = mockk()

    private fun executor(access: CloudPairAccess) = RepositoryCloudAiExecutor(
        mockk<AIRepository>(), mockk<SettingsRepository>(), mockk<ToolRegistry>(), network, access
    )

    @Test
    fun `open access with network keeps cloud available`() {
        every { network.isCurrentlyOnline() } returns true
        val cloud = executor(CloudPairAccess.OPEN)
        assertTrue(cloud.isAvailable())
        assertNull(cloud.unavailableMessage())
    }

    @Test
    fun `missing pair closes cloud with its own message`() {
        every { network.isCurrentlyOnline() } returns true
        val cloud = executor { "connect OMNIX" }
        assertFalse(cloud.isAvailable())
        assertEquals("connect OMNIX", cloud.unavailableMessage())
    }

    @Test
    fun `offline is reported as offline even without a pair`() {
        every { network.isCurrentlyOnline() } returns false
        val cloud = executor { "connect OMNIX" }
        assertFalse(cloud.isAvailable())
        assertNull(cloud.unavailableMessage())
    }
}
