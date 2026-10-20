package com.omnix.assistant.agent.decision

import com.omnix.assistant.agent.localai.LocalModelState

/** Почему выбранная модель не включилась; прежний выбор при этом остаётся. */
enum class AiModelActivationError {
    /** Модель на телефоне не скачана. */
    LOCAL_NOT_INSTALLED,

    /** Модель ещё скачивается. */
    LOCAL_DOWNLOADING,

    /** На телефоне не хватает памяти, чтобы загрузить модель. */
    LOCAL_NOT_ENOUGH_MEMORY,

    /** Модель есть, но не загрузилась (битый файл, сбой движка). */
    LOCAL_LOAD_FAILED,

    /** Сервер OMNIX не ответил: облачная модель сейчас недоступна. */
    SERVER_UNREACHABLE
}

/** Итог попытки сменить модель. */
sealed interface AiModelActivation {
    data object Applied : AiModelActivation
    data class Failed(val error: AiModelActivationError, val detail: String? = null) : AiModelActivation
}

/**
 * Смена ИИ-модели с проверкой: выбор сохраняется, только когда новая модель
 * действительно готова отвечать. Не включилась — сохранённый выбор не
 * трогаем, пользователь остаётся на прежней рабочей модели.
 *
 * Чистая логика без Android: зависимости приходят функциями, поэтому класс
 * покрыт JVM-тестами.
 */
class AiModelActivator(
    private val loadLocalModel: suspend () -> LocalModelState,
    private val serverReachable: suspend () -> Boolean,
    private val save: suspend (AiModelChoice) -> Unit
) {

    suspend fun activate(target: AiModelChoice): AiModelActivation {
        val failure = when (AiModelChoiceCatalog.requirementOf(target)) {
            AiModelRequirement.NONE -> null
            AiModelRequirement.LOCAL_MODEL -> checkLocal(loadLocalModel())
            AiModelRequirement.SERVER -> if (serverReachable()) null else {
                AiModelActivation.Failed(AiModelActivationError.SERVER_UNREACHABLE)
            }
        }
        if (failure != null) return failure
        save(target)
        return AiModelActivation.Applied
    }

    private fun checkLocal(state: LocalModelState): AiModelActivation.Failed? = when (state) {
        is LocalModelState.Ready -> null
        is LocalModelState.NotInstalled -> AiModelActivation.Failed(AiModelActivationError.LOCAL_NOT_INSTALLED)
        is LocalModelState.Downloading -> AiModelActivation.Failed(AiModelActivationError.LOCAL_DOWNLOADING)
        is LocalModelState.InsufficientMemory ->
            AiModelActivation.Failed(AiModelActivationError.LOCAL_NOT_ENOUGH_MEMORY)
        is LocalModelState.DownloadFailed ->
            AiModelActivation.Failed(AiModelActivationError.LOCAL_NOT_INSTALLED, state.reason)
        is LocalModelState.Failed ->
            AiModelActivation.Failed(AiModelActivationError.LOCAL_LOAD_FAILED, state.reason)
        LocalModelState.NotInitialized, LocalModelState.Loading ->
            AiModelActivation.Failed(AiModelActivationError.LOCAL_LOAD_FAILED)
    }
}
