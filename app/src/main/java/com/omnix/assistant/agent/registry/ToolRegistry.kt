package com.omnix.assistant.agent.registry

import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.discovery.ToolDiscoveryEngine
import com.omnix.assistant.agent.model.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards OmniTool>,
    private val discoveryEngine: ToolDiscoveryEngine
) {
    private val toolsById: Map<String, OmniTool>
    private val toolsByName: Map<String, OmniTool>

    init {
        require(tools.none { it.toolId.isBlank() }) { "Tool id must not be blank" }
        val duplicateIds = tools.groupBy { it.toolId }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate tool ids: ${duplicateIds.joinToString()}" }

        val aliases = tools.groupBy { it.name.substringAfterLast(".") }
        val duplicateAliases = aliases.filterValues { it.size > 1 }.keys
        require(duplicateAliases.isEmpty()) { "Duplicate tool aliases: ${duplicateAliases.joinToString()}" }

        toolsById = tools.associateBy { it.toolId }
        toolsByName = tools.associateBy { it.name.substringAfterLast(".") }
    }

    fun getTool(identifier: String): OmniTool? {
        return toolsById[identifier] ?: toolsByName[identifier]
    }

    fun getAllTools(): List<OmniTool> = toolsById.values.toList()

    fun getToolsByCategory(category: ToolCategory): List<OmniTool> {
        return toolsById.values.filter { it.category == category }
    }

    fun getToolDefinitions(): List<ToolDefinition> = toolsById.values.map { it.toDefinition() }

    /**
     * Tool Discovery 2.0: Динамически отбирает 3-4 инструмента под конкретный запрос
     */
    fun discoverRelevantTools(userQuery: String, maxTools: Int = 4): List<OmniTool> {
        return discoveryEngine.discoverTools(userQuery, getAllTools(), maxTools)
    }

    /**
     * Формирует сжатый системный промпт ТОЛЬКО для найденных через Discovery инструментов
     */
    fun buildTargetedSystemPrompt(userQuery: String): String {
        val discovered = discoverRelevantTools(userQuery)
        return discoveryEngine.buildTargetedToolsPrompt(discovered)
    }

}
