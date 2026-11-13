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
 * V012: без подключённой пары OMNIX текстовый чат идёт в облако, а голос и
 * инструменты из облака — нет (со своим сообщением); без сети — обычное
 * офлайн-сообщение (null).
 */
class CloudPairAccessTest {

    private val network: NetworkMonitor = mockk()

    /** Аккаунт с парой, наушники не подключены: чат можно, остальное нельзя. */
    private val chatOnly = CloudPairAccess { use -> if (use == CloudUse.CHAT) null else "chat only" }

    private fun executor(access: CloudPairAccess) = RepositoryCloudAiExecutor(
        mockk<AIRepository>(), mockk<SettingsRepository>(), mockk<ToolRegistry>(), network, access
    )

    @Test
    fun `open access with network keeps cloud available`() {
        every { network.isCurrentlyOnline() } returns true
        val cloud = executor(CloudPairAccess.OPEN)
        assertTrue(cloud.isAvailable())
        assertTrue(cloud.isAvailableFor(RequestSource.VOICE))
        assertNull(cloud.unavailableMessage(RequestSource.VOICE))
        assertNull(cloud.toolsBlockedMessage())
    }

    @Test
    fun `without connected earbuds only chat reaches the cloud`() {
        every { network.isCurrentlyOnline() } returns true
        val cloud = executor(chatOnly)
        assertTrue(cloud.isAvailableFor(RequestSource.CHAT))
        assertNull(cloud.unavailableMessage(RequestSource.CHAT))
        assertFalse(cloud.isAvailableFor(RequestSource.VOICE))
        assertFalse(cloud.isAvailable())
        assertEquals("chat only", cloud.unavailableMessage(RequestSource.VOICE))
        assertEquals("chat only", cloud.toolsBlockedMessage())
    }

    @Test
    fun `offline is reported as offline even without a pair`() {
        every { network.isCurrentlyOnline() } returns false
        val cloud = executor(chatOnly)
        assertFalse(cloud.isAvailableFor(RequestSource.CHAT))
        assertNull(cloud.unavailableMessage(RequestSource.VOICE))
    }
}
