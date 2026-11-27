package com.omnix.assistant.voice.offline

import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelSpec
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.localai.downloader.ModelDownloadPolicy
import com.omnix.assistant.core.locale.AppLanguage
import com.omnix.assistant.data.preferences.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Офлайн-режим одним пакетом: локальный ИИ + туркменский голос + туркменское
 * распознавание. Скачивается одной кнопкой; пока пакет не на телефоне, приложение
 * при каждом запуске предлагает его скачать (решение владельца 2026-11-12).
 *
 * Части качаются независимо (каждая своим DownloadManager-заданием), поэтому
 * уже скачанная часть повторно не качается, а сбой одной не останавливает другие.
 */
@Singleton
class OfflinePack @Inject constructor(
    private val localModel: LocalModelManager,
    private val localSpec: LocalModelSpec,
    private val speech: OfflineSpeechModelStore,
    private val settings: SettingsDataStore
) {
    /** Голос и распознавание, которые входят в пакет (только опубликованные). */
    val speechParts: List<OfflineSpeechModel>
        get() = listOf(OfflineSpeechModels.TK_VOICE, OfflineSpeechModels.RU_VOICE, OfflineSpeechModels.TK_ASR)
            .filter { it.isPublished }

    /** Полный размер пакета, МБ — для кнопки и вопроса при запуске. */
    val totalSizeMb: Int
        get() = localSpec.approxSizeMb + speechParts.sumOf { it.approxSizeMb }

    /** Все части на телефоне. */
    suspend fun isInstalled(): Boolean =
        localModel.isModelInstalled() && speechParts.all { speech.isInstalled(it) }

    /** Хоть одна часть сейчас качается или распаковывается. */
    fun isDownloading(localState: LocalModelState): Boolean =
        localState is LocalModelState.Downloading || speechParts.any { speech.isBusy(it) }

    /**
     * Скачать всё, чего нет. [overMetered] = false — только по Wi-Fi.
     * Повторный вызов во время загрузки безопасен: идущие задания не дублируются.
     */
    suspend fun downloadAll(overMetered: Boolean) {
        if (!localModel.isModelInstalled()) {
            settings.setLocalModelConsent(
                if (overMetered) ModelDownloadPolicy.CONSENT_ANY_NETWORK else ModelDownloadPolicy.CONSENT_WIFI_ONLY
            )
            localModel.ensureModel()
        }
        speechParts.filterNot { speech.isInstalled(it) }.forEach { speech.download(it, overMetered) }
    }

    /**
     * Свой голос OMNIX — основной, а не «по желанию» (решение владельца 2026-11-26): при
     * русском языке приложения голос качается сам, только по Wi-Fi, без вопроса. Пока он не
     * на телефоне, говорит системный голос. Повторный вызов во время загрузки ничего не делает.
     */
    fun ensurePrimaryVoice() {
        // Голос и улучшенное распознавание без интернета (GigaAM): основные для языка, а не «по желанию».
        val models = when (AppLanguage.current()) {
            AppLanguage.RU -> listOf(OfflineSpeechModels.RU_VOICE, OfflineSpeechModels.RU_ASR)
            // Туркменский: без модели на телефоне фразы уходили бы на сервер, а он туркменский почти
            // не понимает (замер 27.11: WER 122%).
            AppLanguage.TK -> listOf(OfflineSpeechModels.TK_VOICE, OfflineSpeechModels.TK_ASR)
            else -> return
        }
        models.filter { it.isPublished && !speech.isInstalled(it) && !speech.isBusy(it) }
            .forEach { speech.download(it, overMetered = false) }
        // Новая туркменская модель на месте — прежняя (270 МБ) больше не нужна.
        if (speech.isInstalled(OfflineSpeechModels.TK_ASR)) speech.removeLegacy()
    }
}
