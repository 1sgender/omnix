package com.omnix.assistant.presentation.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.AiTier
import com.omnix.assistant.agent.decision.AiTierSwitch
import com.omnix.assistant.agent.decision.PrivacyClassification
import com.omnix.assistant.agent.decision.PrivacyClassifier
import com.omnix.assistant.agent.decision.PrivacyContent
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.tools.accessibility.ScreenContentPrivacy
import com.omnix.assistant.agent.decision.PrivacyReason
import com.omnix.assistant.agent.executor.ToolExecutor
import com.omnix.assistant.agent.model.ToolCall
import com.omnix.assistant.core.confirmation.ConfirmationIntent
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.chat.ChatAnswerOriginStore
import com.omnix.assistant.domain.chat.ChatConversationStore
import com.omnix.assistant.domain.models.ConversationSummary
import android.util.Log
import com.omnix.assistant.domain.models.Message
import kotlinx.coroutines.CancellationException
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.domain.models.PromptExecutionResult
import com.omnix.assistant.domain.repository.MessageRepository
import com.omnix.assistant.domain.usecases.ClearChatHistoryUseCase
import com.omnix.assistant.domain.usecases.GetChatHistoryUseCase
import com.omnix.assistant.domain.usecases.GetSettingsUseCase
import com.omnix.assistant.domain.usecases.SendPromptUseCase
import com.omnix.assistant.voice.stt.SpeechRecognitionEvent
import com.omnix.assistant.voice.stt.SpeechRecognizerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Отложенное действие, ожидающее подтверждения пользователя в чате. */
data class PendingConfirmationUi(
    val toolCall: ToolCall,
    val promptMessage: String,

    /**
     * Одноразовый токен подтверждения (пункт аудита #5): получается из очереди
     * ToolExecutor и передаётся в executeWithBypass для проверки.
     */
    val confirmationToken: String
)

/**
 * C-02: ожидание согласия пользователя на отправку приватного запроса в облако.
 *
 * Отличается от [PendingConfirmationUi] тем, что здесь нет ToolCall/токена
 * подтверждения действия — мы просто запоминаем оригинальный текст запроса,
 * чтобы при нажатии «Отправить в облако» повторить вызов SendPromptUseCase с
 * флагом cloudExplicitlyAllowed=true. При «Только локально» — повторяем с
 * cloudExplicitlyAllowed=false? Нет: «Только локально» означает «не давать
 * согласие», т.е. запрос должен быть обработан локально (on-device Gemma);
 * в текущей реализации если локальный путь не может обработать — вернётся
 * внятная ошибка, а не обход согласия.
 */
data class PendingCloudConsentUi(
    val privacyLevel: PrivacyLevel,
    val promptMessage: String,
    val userPrompt: String
)

/** Файл, выбранный для следующего сообщения: текст уже извлечён. */
data class PendingAttachmentUi(
    val name: String,
    val text: String,
    val truncated: Boolean,
    val kind: com.omnix.assistant.domain.files.FileKind = com.omnix.assistant.domain.files.FileKind.TEXT,
    val sizeBytes: Long = -1,
    /** PDF, сохранённый для отправки модели целиком. */
    val documentId: String? = null
)

/** Что сейчас происходит с выбранным файлом: над полем ввода «Читаю файл…». */
enum class AttachmentWork { READING, TRANSCRIBING }

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    /** Приложенный к вводу файл; уйдёт вместе со следующим сообщением. */
    val attachment: PendingAttachmentUi? = null,
    /** Почему файл не удалось приложить; показывается над полем ввода. */
    val attachmentError: com.omnix.assistant.domain.files.FileTextExtractor.Reason? = null,
    /** Файл читается или аудио расшифровывается. */
    val attachmentProcessing: AttachmentWork? = null,
    /** Фото, выбранное для следующего сообщения (id файла в ChatImageStore). */
    val pendingImageId: String? = null,
    /** Фото сжимается: над полем ввода «Готовлю фото…». */
    val imageProcessing: Boolean = false,
    /** Почему фото не удалось приложить. */
    val imageError: com.omnix.assistant.domain.files.ChatImageRules.Reject? = null,
    val inputText: String = "",
    val isSending: Boolean = false,
    val isVoiceDictating: Boolean = false,
    val privacyClassification: PrivacyClassification =
        PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED),
    val pendingConfirmation: PendingConfirmationUi? = null,
    /** C-02: запрос на отправку приватных данных в облако, ожидающий ответа пользователя. */
    val pendingCloudConsent: PendingCloudConsentUi? = null,
    /** id ответов ассистента из облачной модели: под ними «Ответ из облака». */
    val cloudMessageIds: Set<Long> = emptySet(),
    /** Тест моделей (только админ): id ответа → кто ответил. */
    val adminLabels: Map<Long, String> = emptyMap(),
    /** Облачный ответ → кто ответил (Flash / Pro): моно-метка под ответом. */
    val answeredTiers: Map<Long, AiTier> = emptyMap(),
    /** Выбран Pro, а ответил Flash: под ответом «Ответил Flash». */
    val flashFallbackIds: Set<Long> = emptySet(),
    /** Сообщения «лимит Pro исчерпан»: под ними «Переключиться на Flash». */
    val proQuotaIds: Set<Long> = emptySet(),
    /**
     * Ответ, который нельзя хранить в истории (подтверждённое чтение экрана):
     * виден в ленте до следующего сообщения. Раньше его только озвучивали.
     */
    val localOnlyAnswer: String? = null,
    /** Открыт ли список «История» вместо ленты. */
    val showHistory: Boolean = false,
    /** Прошлые разговоры, свежие первыми. */
    val conversations: List<ConversationSummary> = emptyList(),
    val currentConversationId: Long = 0L
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getChatHistoryUseCase: GetChatHistoryUseCase,
    private val clearChatHistoryUseCase: ClearChatHistoryUseCase,
    private val sendPromptUseCase: SendPromptUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val toolExecutor: ToolExecutor,
    private val messageRepository: MessageRepository,
    private val answerOriginStore: ChatAnswerOriginStore,
    private val conversationStore: ChatConversationStore,
    private val tierSwitch: AiTierSwitch = AiTierSwitch.NONE,
    private val attachmentReader: com.omnix.assistant.data.files.AttachmentReader =
        com.omnix.assistant.data.files.AttachmentReader(context),
    private val imageStore: com.omnix.assistant.data.files.ChatImageStore =
        com.omnix.assistant.data.files.ChatImageStore(context),
    private val documentStore: com.omnix.assistant.data.files.ChatDocumentStore =
        com.omnix.assistant.data.files.ChatDocumentStore(context),
    private val audioTranscriber: com.omnix.assistant.data.files.AudioTranscriber =
        com.omnix.assistant.data.files.AudioTranscriber.NONE
) : ViewModel() {

    companion object {
        private const val TAG_SEND = "ChatViewModel"

        /**
         * CR-17: дебаунс для PrivacyClassifier при быстром вводе с клавиатуры.
         * Меньше 150 мс — дёргает классификатор на каждый слог; больше 400 мс —
         * индикатор в UI ощущается отставшим. 250 мс — стандартный UX-компромисс.
         */
        private const val CLASSIFY_DEBOUNCE_MS = 250L
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var systemPrompt = ""

    /**
     * CR-17: буфер текста из onInputTextChanged. Вместо запуска PrivacyClassifier
     * на каждый keystroke, тексты сливаются через [debounce] (250 мс) и последняя
     * актуальная версия классифицируется ровно один раз. При быстром вводе /
     * lifecycle destruction старая классификация не применяется к новому тексту.
     */
    private val _inputClassificationRequests = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var inputClassificationJob: Job? = null

    init {
        loadHistory()
        observeSettings()
        observeSpeechRecognizer()
        startInputClassification()
    }

    /**
     * CR-17: один подписчик на поток ввода. Используем collectLatest: новая эмиссия
     * автоматически отменяет предыдущую classify/задержку, исключая race stale->new.
     */
    private fun startInputClassification() {
        inputClassificationJob?.cancel()
        inputClassificationJob = viewModelScope.launch {
            _inputClassificationRequests
                .debounce(CLASSIFY_DEBOUNCE_MS)
                .conflate()
                .collectLatest { text ->
                    val classification = PrivacyClassifier.classifySafely(PrivacyContent(text))
                    // atomic CAS: обновляем поле ТОЛЬКО если inputText всё ещё совпадает
                    // (защита от stale результата на случай, если экран уничтожен между
                    // классификацией и применением).
                    _uiState.update { current ->
                        if (current.inputText == text) current.copy(privacyClassification = classification)
                        else current
                    }
                }
        }
    }

    /** Кончился лимит Pro: одно нажатие — дальше отвечает Flash, кнопки под сообщениями о лимите убираем. */
    fun switchToFlash() {
        viewModelScope.launch {
            tierSwitch.switchToFlash()
            answerOriginStore.clearProQuota()
        }
    }

    override fun onCleared() {
        super.onCleared()
        inputClassificationJob?.cancel()
        inputClassificationJob = null
    }

    private fun loadHistory() {
        viewModelScope.launch {
            getChatHistoryUseCase().collectLatest { messageList ->
                _uiState.update { it.copy(messages = messageList) }
            }
        }
        // «Ответ из облака»: метки проставляет SendPromptUseCase при сохранении
        // ответа — стрим мерджится в состояние независимо от истории.
        viewModelScope.launch {
            answerOriginStore.cloudMessageIds.collectLatest { ids ->
                _uiState.update { it.copy(cloudMessageIds = ids) }
            }
        }
        viewModelScope.launch {
            answerOriginStore.adminLabels.collectLatest { labels ->
                _uiState.update { it.copy(adminLabels = labels) }
            }
        }
        viewModelScope.launch {
            answerOriginStore.answeredTiers.collectLatest { tiers ->
                _uiState.update { it.copy(answeredTiers = tiers) }
            }
        }
        viewModelScope.launch {
            answerOriginStore.flashFallbackIds.collectLatest { ids ->
                _uiState.update { it.copy(flashFallbackIds = ids) }
            }
        }
        viewModelScope.launch {
            answerOriginStore.proQuotaIds.collectLatest { ids ->
                _uiState.update { it.copy(proQuotaIds = ids) }
            }
        }
        viewModelScope.launch {
            conversationStore.currentId.collectLatest { id ->
                _uiState.update { it.copy(currentConversationId = id) }
            }
        }
        viewModelScope.launch {
            messageRepository.getConversations().collectLatest { list ->
                _uiState.update { it.copy(conversations = list) }
            }
        }
    }

    /** «Новый чат»: пустой разговор, прошлый остаётся в истории. */
    fun startNewConversation() {
        conversationStore.startNew()
        _uiState.update { it.copy(localOnlyAnswer = null, showHistory = false) }
    }

    fun openHistory() {
        _uiState.update { it.copy(showHistory = true) }
    }

    fun closeHistory() {
        _uiState.update { it.copy(showHistory = false) }
    }

    /** Открыть прошлый разговор: его можно читать и продолжать. */
    fun openConversation(conversationId: Long) {
        conversationStore.open(conversationId)
        _uiState.update { it.copy(localOnlyAnswer = null, showHistory = false) }
    }

    /** Удалить разговор из истории; если он открыт — начать новый. */
    fun deleteConversation(conversationId: Long) {
        if (conversationId == conversationStore.currentId.value) {
            conversationStore.startNew()
            _uiState.update { it.copy(localOnlyAnswer = null) }
        }
        viewModelScope.launch {
            messageRepository.deleteConversation(conversationId)
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            getSettingsUseCase().collectLatest { settings ->
                systemPrompt = settings.systemPrompt
            }
        }
    }

    /**
     * Распознаватель речи общий с голосовым ассистентом. Чат берёт только ту
     * речь, которую сам начал кнопкой диктовки: раньше любая фраза, услышанная
     * ассистентом (в том числе голос самого OMNIX после ложного «Omni»),
     * уходила в чат как сообщение пользователя — OMNIX говорил сам с собой
     * (отчёт владельца 2026-10-21). Продиктованный текст остаётся в поле
     * ввода: отправляет пользователь.
     */
    private fun observeSpeechRecognizer() {
        viewModelScope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                if (!_uiState.value.isVoiceDictating) return@collectLatest
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        // CR-17: голосовой промежуточный текст тоже идёт через debounce
                        // классификацию (тот же pipeline, что и клавиатурный ввод).
                        val text = withDictationPrefix(event.partialText)
                        if (_uiState.value.inputText != text) {
                            _uiState.update { it.copy(inputText = text) }
                            _inputClassificationRequests.tryEmit(text)
                        }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        val text = withDictationPrefix(event.recognizedText)
                        _uiState.update { it.copy(inputText = text, isVoiceDictating = false) }
                        _inputClassificationRequests.tryEmit(text)
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        _uiState.update { it.copy(isVoiceDictating = false) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onInputTextChanged(newText: String) {
        // CR-17: inputText обновляем немедленно (UI binding), но классификацию
        // запускаем через debounce поток. Пустой ввод и идентичные фрагменты
        // short-circuit, чтобы не плодить work.
        val prev = _uiState.value
        if (newText == prev.inputText) return
        _uiState.update {
            it.copy(
                inputText = newText,
                // При пустом вводе нет смысла показывать старую метку — сбрасываем
                // сразу (это не race: речь про пустую строку).
                privacyClassification = if (newText.isEmpty()) {
                    PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED)
                } else {
                    it.privacyClassification
                }
            )
        }
        // tryEmit с DROP_OLDEST: в буфере всегда последнее актуальное значение.
        _inputClassificationRequests.tryEmit(newText)
    }

    /**
     * Пользователь выбрал файл в системном окне: читаем и кладём в поле ввода.
     * Фото уходит в обработку картинок, аудио — в расшифровку.
     */
    fun onAttachmentPicked(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(attachmentProcessing = AttachmentWork.READING, attachmentError = null) }
            when (val result = attachmentReader.read(uri)) {
                is com.omnix.assistant.domain.files.FileTextExtractor.Result.Success ->
                    acceptAttachment(
                        PendingAttachmentUi(
                            name = result.name,
                            text = result.text,
                            truncated = result.truncated,
                            kind = result.kind,
                            sizeBytes = result.sizeBytes,
                            documentId = result.documentId
                        )
                    )
                is com.omnix.assistant.domain.files.FileTextExtractor.Result.Failure ->
                    _uiState.update { it.copy(attachmentProcessing = null, attachmentError = result.reason) }
                is com.omnix.assistant.domain.files.FileTextExtractor.Result.Media ->
                    if (result.kind == com.omnix.assistant.domain.files.FileKind.IMAGE) {
                        _uiState.update { it.copy(attachmentProcessing = null) }
                        onImagePicked(uri)
                    } else {
                        transcribeAudio(uri, result.name)
                    }
            }
        }
    }

    private suspend fun transcribeAudio(uri: android.net.Uri, name: String) {
        _uiState.update { it.copy(attachmentProcessing = AttachmentWork.TRANSCRIBING) }
        val size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            attachmentReader.info(uri).sizeBytes
        }
        val reason = when (val outcome = audioTranscriber.transcribe(uri)) {
            is com.omnix.assistant.data.files.AudioTranscriber.Outcome.Success -> {
                val fitted = com.omnix.assistant.domain.files.TextBudget.fit(
                    outcome.text,
                    com.omnix.assistant.domain.files.FileTextExtractor.MAX_TEXT_TOKENS
                )
                acceptAttachment(
                    PendingAttachmentUi(
                        name = name,
                        text = fitted.text,
                        truncated = fitted.truncated || outcome.truncated,
                        kind = com.omnix.assistant.domain.files.FileKind.AUDIO,
                        sizeBytes = size
                    )
                )
                return
            }
            com.omnix.assistant.data.files.AudioTranscriber.Outcome.Unreadable ->
                com.omnix.assistant.domain.files.FileTextExtractor.Reason.UNREADABLE
            com.omnix.assistant.data.files.AudioTranscriber.Outcome.Empty ->
                com.omnix.assistant.domain.files.FileTextExtractor.Reason.EMPTY
            com.omnix.assistant.data.files.AudioTranscriber.Outcome.Failed ->
                com.omnix.assistant.domain.files.FileTextExtractor.Reason.TRANSCRIPTION_FAILED
        }
        _uiState.update { it.copy(attachmentProcessing = null, attachmentError = reason) }
    }

    /** Файл и фото к одному сообщению не берём: выбранное позже заменяет прежнее. */
    private fun acceptAttachment(pending: PendingAttachmentUi) {
        _uiState.value.pendingImageId?.let(imageStore::delete)
        _uiState.value.attachment?.documentId?.let(documentStore::delete)
        _uiState.update {
            it.copy(
                attachment = pending,
                attachmentError = null,
                attachmentProcessing = null,
                pendingImageId = null,
                imageError = null
            )
        }
    }

    fun clearAttachment() {
        _uiState.value.attachment?.documentId?.let(documentStore::delete)
        _uiState.update { it.copy(attachment = null, attachmentError = null, attachmentProcessing = null) }
    }

    /** Пользователь выбрал фото (галерея или камера): сжимаем и кладём в поле ввода. */
    fun onImagePicked(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(imageProcessing = true, imageError = null) }
            val result = imageStore.import(uri)
            imageStore.clearCameraCaptures()
            when (result) {
                is com.omnix.assistant.data.files.ChatImageStore.ImportResult.Success -> {
                    _uiState.value.pendingImageId?.let(imageStore::delete)
                    _uiState.value.attachment?.documentId?.let(documentStore::delete)
                    _uiState.update {
                        it.copy(
                            pendingImageId = result.id,
                            imageProcessing = false,
                            imageError = null,
                            attachment = null,
                            attachmentError = null
                        )
                    }
                }
                is com.omnix.assistant.data.files.ChatImageStore.ImportResult.Failure ->
                    _uiState.update { it.copy(imageProcessing = false, imageError = result.reason) }
            }
        }
    }

    fun clearImage() {
        _uiState.value.pendingImageId?.let(imageStore::delete)
        _uiState.update { it.copy(pendingImageId = null, imageError = null, imageProcessing = false) }
    }

    fun sendTextMessage(textToSend: String = _uiState.value.inputText) {
        val typed = textToSend.trim()
        val attachment = _uiState.value.attachment
        val imageId = _uiState.value.pendingImageId
        if ((typed.isBlank() && attachment == null && imageId == null) || _uiState.value.isSending) return
        val isConfirmation = typed.isNotBlank() && isConfirmationReply(typed)
        // Файл уходит в модель блоком в начале сообщения; в ленте он виден чипом.
        // Фото лежит в сообщении ссылкой (в ленте миниатюра), сам файл подставит облачный клиент.
        val query = when {
            imageId != null && !isConfirmation ->
                com.omnix.assistant.domain.files.ChatImageCodec.encode(
                    imageId,
                    typed.ifBlank { context.getString(com.omnix.assistant.R.string.omnix_chat_image_default_question) }
                )
            attachment != null && !isConfirmation ->
                com.omnix.assistant.domain.files.ChatAttachmentCodec.encode(
                    fileName = attachment.name,
                    content = attachment.text,
                    question = typed.ifBlank {
                        context.getString(com.omnix.assistant.R.string.omnix_chat_file_default_question)
                    },
                    kind = attachment.kind,
                    sizeBytes = attachment.sizeBytes,
                    documentId = attachment.documentId
                )
            else -> typed
        }
        _uiState.update { it.copy(localOnlyAnswer = null) }

        // C-02: приоритет №1 — если висит cloud-consent вопрос, «да/нет» — ответ на него.
        val pendingConsent = _uiState.value.pendingCloudConsent
        if (pendingConsent != null && ConfirmationIntent.isDefinitive(query)) {
            _uiState.update { it.copy(inputText = "") }
            if (ConfirmationIntent.isYes(query)) {
                confirmCloudConsent(pendingConsent)
            } else {
                denyCloudConsent(pendingConsent)
            }
            return
        }

        // Если ждём подтверждения действия — текст «да/нет» обрабатывается как ответ.
        val pending = _uiState.value.pendingConfirmation
        if (pending != null && ConfirmationIntent.isDefinitive(query)) {
            _uiState.update { it.copy(inputText = "") }
            if (ConfirmationIntent.isYes(query)) {
                confirmPendingAction()
            } else {
                cancelPendingAction()
            }
            return
        }

        // H-02: НЕ пересчитываем классификацию на send. Она 1) уже посчитана
        // debounce-пайплайном для индикатора (keystroke-level, без истории),
        // 2) будет ПОЛНОСТЬЮ пересчитана в SendPromptUseCase с контекстом
        // (systemPrompt + история) — это authoritative classification.
        // Здесь передаём подсказку (hint) от UI на основании текущей метки;
        // больше ничего — use case является единственным источником истины.
        val sendHint = _uiState.value.privacyClassification.level
        _uiState.update {
            it.copy(
                inputText = "",
                attachment = null,
                attachmentError = null,
                pendingImageId = null,
                imageError = null,
                isSending = true,
                // C-02: новый сброс старого consent, если начат новый запрос.
                pendingCloudConsent = null
            )
        }

        viewModelScope.launch {
            val result = try {
                sendPromptUseCase(
                    query,
                    privacyLevel = sendHint
                )
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                // Сбой внутри конвейера не должен ронять приложение: пишем его в
                // журнал, а пользователю показываем ошибку в ленте (там же
                // кнопка «Повторить»).
                Log.e(TAG_SEND, "sendTextMessage failed", e)
                reportSendFailure()
                return@launch
            }
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                is Resource.Success -> {
                    when (val exec = result.data) {
                        is PromptExecutionResult.ConfirmationRequired -> {
                            // CR-04: токен берём по callId, а не из головы очереди.
                            // При параллельных запросах из чата и голоса peek() мог
                            // вернуть токен ДРУГОЙ записи и подтвердить не тот вызов.
                            val token = toolExecutor.confirmationTokenFor(exec.toolCall.callId)
                            _uiState.update {
                                it.copy(
                                    pendingConfirmation = if (token != null) {
                                        PendingConfirmationUi(
                                            toolCall = exec.toolCall,
                                            promptMessage = exec.promptMessage,
                                            confirmationToken = token
                                        )
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                        // Ответ уже в истории (его сохраняет SendPromptUseCase).
                        is PromptExecutionResult.DirectAnswer -> Unit
                    }
                }
                // Ошибка тоже сохранена в историю (MessageRole.ERROR) и видна в ленте.
                is Resource.Error -> Unit
                is Resource.Loading -> Unit
                // C-02: use case сигнализирует, что нужен согласие на облако.
                is Resource.NeedsConsent -> {
                    _uiState.update {
                        it.copy(
                            pendingCloudConsent = PendingCloudConsentUi(
                                privacyLevel = result.privacyLevel,
                                promptMessage = result.prompt,
                                userPrompt = result.retryOnConsentArgs.userPrompt
                            )
                        )
                    }
                }
            }
        }
    }

    /** «Да»/«нет» при открытом вопросе отвечает на него, а не отправляет файл. */
    private fun isConfirmationReply(text: String): Boolean {
        val state = _uiState.value
        return (state.pendingCloudConsent != null || state.pendingConfirmation != null) &&
            ConfirmationIntent.isDefinitive(text)
    }

    /** Снимает «отправляется» и кладёт в ленту ошибку (ERROR-пузырь с «Повторить»). */
    private suspend fun reportSendFailure() {
        _uiState.update { it.copy(isSending = false) }
        try {
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ERROR,
                    text = context.getString(R.string.omnix_chat_send_failed),
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG_SEND, "reportSendFailure: не удалось сохранить сообщение об ошибке", e)
        }
    }

    /**
     * C-02: пользователь согласился отправить приватный запрос в облако.
     *
     * Повторяем вызов [SendPromptUseCase] с cloudExplicitlyAllowed=true —
     * use case на этот раз пройдёт privacy gate и отправит запрос в cloud AI.
     */
    fun confirmCloudConsent(consent: PendingCloudConsentUi? = _uiState.value.pendingCloudConsent) {
        // `return` запрещён в default-выражении параметра, поэтому дефолт —
        // nullable, а выход — первый строкой тела (семантика не изменилась).
        if (consent == null) return
        _uiState.update { it.copy(pendingCloudConsent = null, isSending = true) }

        // H-02: НЕ пересчитываем классификацию — use case сам сделает это с полным
        // контекстом на момент consent-retry. Уровень из consent.privacyLevel — это
        // effective, который был посчитан use case в момент первого вызова и уже
        // показан в UI-карточке; его достаточно для hint.
        viewModelScope.launch {
            val result = try {
                sendPromptUseCase(
                    userPrompt = consent.userPrompt,
                    source = com.omnix.assistant.agent.decision.RequestSource.CHAT,
                    privacyLevel = consent.privacyLevel,
                    cloudExplicitlyAllowed = true
                )
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSending = false) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG_SEND, "confirmCloudConsent failed", e)
                reportSendFailure()
                return@launch
            }
            _uiState.update { it.copy(isSending = false) }
            when (result) {
                is Resource.Success -> {
                    when (val exec = result.data) {
                        is PromptExecutionResult.ConfirmationRequired -> {
                            val token = toolExecutor.confirmationTokenFor(exec.toolCall.callId)
                            _uiState.update {
                                it.copy(
                                    pendingConfirmation = if (token != null) PendingConfirmationUi(
                                        toolCall = exec.toolCall,
                                        promptMessage = exec.promptMessage,
                                        confirmationToken = token
                                    ) else null
                                )
                            }
                        }
                        is PromptExecutionResult.DirectAnswer -> Unit
                    }
                }
                is Resource.Error -> Unit
                is Resource.Loading -> Unit
                // Теоретически не может прийти NeedsConsent второй раз — флаг уже true.
                is Resource.NeedsConsent -> Unit
            }
        }
    }

    /**
     * C-02: пользователь отказался отправлять запрос в облако.
     *
     * В первой версии просто показываем отказ и НЕ делаем запрос в облако.
     * Полноценный fallback на Local AI (on-device Gemma) с этим флагом — это
     * отдельная работа Этап 2, когда local executor будет стабилен; сейчас
     * локальный путь сам сработает в decision engine, если модель доступна
     * и способна обработать запрос.
     */
    fun denyCloudConsent(consent: PendingCloudConsentUi? = _uiState.value.pendingCloudConsent) {
        if (consent == null) return
        _uiState.update { it.copy(pendingCloudConsent = null) }
        val declineMsg = context.getString(R.string.cloud_consent_declined)
        viewModelScope.launch {
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = declineMsg,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Пользователь подтвердил отложенное действие (кнопка «Подтвердить» или «Да»).
     * Выполняется БЕЗ повторного гейта подтверждения — как после голосового «Да».
     *
     * Пункт аудита #4: после выполнения показываем СЛЕДУЮЩИЙ ожидающий
     * подтверждения вызов из очереди ToolExecutor, если он есть.
     */
    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null, isSending = true) }

        viewModelScope.launch {
            val result = toolExecutor.executeWithBypass(
                call = pending.toolCall,
                confirmationToken = pending.confirmationToken,
                source = "chat_ui"
            )
            val voiceResponse = when {
                result.isSuccess -> context.getString(R.string.s_sir, result.summary)
                result.isBlockedByAndroid -> result.summary
                else -> context.getString(R.string.ne_udalos_vypolnit, result.error ?: result.summary)
            }
            // Accessibility Lockdown: подтверждённое чтение экрана озвучивается,
            // но в историю пишется placeholder — иначе bypass-результат утёк бы
            // в следующий облачный запрос через history (ScreenContentPrivacy).
            val persistedText =
                if (result.isSuccess && ScreenContentPrivacy.isScreenReaderCall(pending.toolCall.toolId)) {
                    ScreenContentPrivacy.PLACEHOLDER
                } else {
                    voiceResponse
                }
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = persistedText,
                    timestamp = System.currentTimeMillis()
                )
            )
            // Результат чтения экрана в историю не пишется (см. выше), поэтому
            // показываем его в ленте до следующего сообщения, не сохраняя.
            _uiState.update {
                it.copy(
                    isSending = false,
                    localOnlyAnswer = if (persistedText != voiceResponse) voiceResponse else null
                )
            }

            // Следующий запрос подтверждения из очереди (если есть).
            toolExecutor.peekPendingConfirmation()?.let { next ->
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmationUi(
                            toolCall = next.toolCall,
                            promptMessage = next.promptMessage,
                            confirmationToken = next.confirmationToken
                        )
                    )
                }
            }
        }
    }

    /** Пользователь отклонил отложенное действие (кнопка «Отмена» или «Нет»). */
    fun cancelPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null) }
        // Пункт аудита #4: отменяем ТОЛЬКО текущий вызов — остальные в очереди не трогаем.
        toolExecutor.removePendingConfirmation(pending.toolCall)

        viewModelScope.launch {
            val cancelMsg = context.getString(R.string.operaciya_otmenena_sir)
            messageRepository.insertMessage(
                Message(
                    role = MessageRole.ASSISTANT,
                    text = cancelMsg,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Показываем следующий ожидающий подтверждения, если есть.
            toolExecutor.peekPendingConfirmation()?.let { next ->
                _uiState.update {
                    it.copy(
                        pendingConfirmation = PendingConfirmationUi(
                            toolCall = next.toolCall,
                            promptMessage = next.promptMessage,
                            confirmationToken = next.confirmationToken
                        )
                    )
                }
            }
        }
    }

    /**
     * Текст, который был в поле до нажатия микрофона: диктовка дописывает
     * к нему, а не стирает набранное (как в ChatGPT).
     */
    private var dictationPrefix = ""

    private fun withDictationPrefix(spoken: String): String = when {
        dictationPrefix.isBlank() -> spoken
        spoken.isBlank() -> dictationPrefix
        else -> dictationPrefix.trimEnd() + " " + spoken.trimStart()
    }

    fun toggleVoiceDictation() {
        if (_uiState.value.isVoiceDictating) {
            speechRecognizerManager.stopListening()
            _uiState.update { it.copy(isVoiceDictating = false) }
        } else {
            dictationPrefix = _uiState.value.inputText
            _uiState.update { it.copy(isVoiceDictating = true) }
            speechRecognizerManager.startListening()
        }
    }

    fun clearAllHistory() {
        _uiState.update { it.copy(localOnlyAnswer = null, pendingImageId = null, imageError = null) }
        viewModelScope.launch {
            clearChatHistoryUseCase()
            // Вместе с историей стираем и сжатые фото из чата.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                imageStore.deleteAll()
                documentStore.deleteAll()
            }
        }
    }
}
