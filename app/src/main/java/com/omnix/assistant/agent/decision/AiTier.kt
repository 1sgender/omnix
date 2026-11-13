package com.omnix.assistant.agent.decision

import com.omnix.assistant.core.license.PlanEntitlements

/**
 * Уровень облачного ответа: что просим у сервера (поле `tier` запроса) и
 * что он фактически отдал (поле `tier` ответа).
 */
enum class AiTier(val wireValue: String) {
    FLASH("flash"),
    PRO("pro");

    companion object {
        /**
         * Ответ сервера: всё, кроме "pro" (в том числе старый сервер без поля),
         * — Flash. Ответ никогда не назван Pro по ошибке.
         */
        fun fromWire(value: String?): AiTier =
            if (value?.trim().equals(PRO.wireValue, ignoreCase = true)) PRO else FLASH
    }
}

/** Доступность Pro по тарифу: единственное место, которое это решает на клиенте. */
object AiProAccess {

    /** Pro есть в тарифе, если на день выдано больше нуля ответов (Free: 0). */
    fun isAvailable(entitlements: PlanEntitlements): Boolean = entitlements.dailyProAi > 0

    /**
     * Какой уровень просить у сервера: Pro — только если он выбран и есть в
     * тарифе. Тариф понизили, а выбор остался Pro — честно просим Flash, а не
     * получаем отказ на каждый вопрос.
     */
    fun requestedTier(choice: AiModelChoice, entitlements: PlanEntitlements): AiTier =
        if (choice == AiModelChoice.PRO && isAvailable(entitlements)) AiTier.PRO else AiTier.FLASH
}

/** Какой уровень просить у сервера для текущего запроса; читается на каждый запрос. */
fun interface AiTierPreference {
    fun requestedTier(): AiTier

    /** Есть ли Pro в тарифе: без него на исчерпанный лимит подсказываем «Больше ответов в Pro и Max». */
    fun proInPlan(): Boolean = false

    companion object {
        /** Без выбора (тесты, legacy-вызовы): всегда Flash. */
        val FLASH: AiTierPreference = AiTierPreference { AiTier.FLASH }
    }
}

/** Переключение с Pro на Flash одним нажатием (кончился лимит Pro). */
interface AiTierSwitch {
    suspend fun switchToFlash()

    companion object {
        /** Ничего не переключает (тесты без DI). */
        val NONE: AiTierSwitch = object : AiTierSwitch {
            override suspend fun switchToFlash() = Unit
        }
    }
}
