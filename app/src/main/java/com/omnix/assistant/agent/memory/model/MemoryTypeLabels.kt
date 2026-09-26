package com.omnix.assistant.agent.memory.model

/**
 * Токены типов воспоминаний для UI (браузер памяти, блок 4 плана
 * пересборки фронта 2026-09-26). Тот же паттерн, что
 * [com.omnix.assistant.agent.automation.model.AutomationDescriptions]:
 * домен отдаёт токен, экран мапит токен в string-ресурс — никакого
 * дублирования строк и никаких сырых enum-имён в интерфейсе.
 *
 * Читается из Room-колонки `type` (пишется как [MemoryType.name]);
 * неизвестное/битое значение — [OTHER], а не краш.
 */
enum class MemoryTypeLabel {
    FACT,
    PREFERENCE,
    EPISODIC,
    PROCEDURAL,
    OTHER
}

fun memoryTypeLabel(raw: String?): MemoryTypeLabel =
    MemoryTypeLabel.entries.firstOrNull { it.name == raw }
        ?: MemoryTypeLabel.OTHER
