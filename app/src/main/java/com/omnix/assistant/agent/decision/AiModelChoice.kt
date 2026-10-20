package com.omnix.assistant.agent.decision

/**
 * Какой ИИ отвечает на запросы, которые не являются командами телефону.
 *
 * Варианты — только то, что приложение реально умеет:
 *  - [AUTO]      — прежнее поведение: локальная модель берёт короткие
 *                  простые запросы, остальное уходит в облако;
 *  - [ON_DEVICE] — только модель на телефоне (сейчас Qwen 2.5 0.5B), без
 *                  облака: работает без интернета, ничего не уходит наружу;
 *  - [CLOUD]     — только облачная модель на сервере OMNIX (провайдера
 *                  выбирает сервер, клиент его не знает и не хранит ключей).
 *
 * Команды телефону («включи фонарик») выполняются устройством при любом
 * выборе — это не ответы модели.
 *
 * Новая модель добавляется новым вариантом здесь и строкой в
 * [AiModelChoiceCatalog]; экран выбора строится по каталогу и переписывать
 * его не нужно.
 */
enum class AiModelChoice(val storageKey: String) {
    AUTO("auto"),
    ON_DEVICE("on_device"),
    CLOUD("cloud");

    companion object {
        /** Неизвестное или пустое значение из хранилища — безопасный [AUTO]. */
        fun fromStorageKey(key: String?): AiModelChoice =
            entries.firstOrNull { it.storageKey == key } ?: AUTO
    }
}

/** Источник текущего выбора для движка решений; читается на каждый запрос. */
fun interface AiModelChoiceSource {
    fun current(): AiModelChoice

    companion object {
        /** Фиксированный выбор (по умолчанию [AiModelChoice.AUTO]) — для тестов и без DI. */
        fun fixed(choice: AiModelChoice = AiModelChoice.AUTO): AiModelChoiceSource =
            AiModelChoiceSource { choice }
    }
}

/** Что нужно варианту, чтобы работать: экран выбора проверяет это перед применением. */
enum class AiModelRequirement {
    /** Ничего: вариант доступен всегда. */
    NONE,

    /** Модель должна быть скачана и загружена на телефоне. */
    LOCAL_MODEL,

    /** Нужна связь с сервером OMNIX. */
    SERVER
}

/** Порядок и требования вариантов: единственное место, которое знает список. */
object AiModelChoiceCatalog {
    val options: List<Pair<AiModelChoice, AiModelRequirement>> = listOf(
        AiModelChoice.AUTO to AiModelRequirement.NONE,
        AiModelChoice.ON_DEVICE to AiModelRequirement.LOCAL_MODEL,
        AiModelChoice.CLOUD to AiModelRequirement.SERVER
    )

    fun requirementOf(choice: AiModelChoice): AiModelRequirement =
        options.first { it.first == choice }.second
}
