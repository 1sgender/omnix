package com.omnix.assistant.agent.decision

/**
 * Смена модели ИИ с проверкой ([AiModelActivator]) — одна для листа выбора
 * в чате и для голосовой команды «переключись на Pro». Выбор сохраняется и
 * действует для всех следующих сообщений.
 */
interface AiModelSwitch {
    /** Сохранённый выбор пользователя (без админского закрепления). */
    fun selected(): AiModelChoice

    /** Уже выбрано — сразу [AiModelActivation.Applied]; иначе проверка и сохранение. */
    suspend fun switchTo(target: AiModelChoice): AiModelActivation
}
