package com.omnix.assistant.di

import android.content.Context
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.agent.automation.dao.AutomationDao
import com.omnix.assistant.agent.capability.CapabilityChecker
import com.omnix.assistant.agent.capability.DeviceCapabilityRegistry
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.decision.AgentExecutor
import com.omnix.assistant.agent.decision.CloudAiExecutor
import com.omnix.assistant.agent.decision.CognitiveAgentExecutor
import com.omnix.assistant.agent.decision.ExecutionDecisionConfig
import com.omnix.assistant.agent.decision.LocalAiExecutor
import com.omnix.assistant.agent.decision.CompositeLocalAiExecutor
import com.omnix.assistant.agent.decision.RepositoryCloudAiExecutor
import com.omnix.assistant.agent.localai.LocalAi
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelSpec
import com.omnix.assistant.agent.localai.LocalPromptBuilder
import com.omnix.assistant.agent.localai.OmniLocalPromptBuilder
import com.omnix.assistant.agent.localai.OnDeviceLocalAi
import com.omnix.assistant.agent.localai.downloader.DownloadManagerModelDownloader
import com.omnix.assistant.agent.localai.downloader.ModelDownloader
import com.omnix.assistant.agent.localai.mediapipe.DefaultMediaPipeRuntimeFactory
import com.omnix.assistant.agent.localai.mediapipe.MediaPipeModelManager
import com.omnix.assistant.agent.localai.pack.AssetManagerPackModelLocator
import com.omnix.assistant.agent.localai.pack.PackModelLocator
import com.omnix.assistant.agent.localai.mediapipe.MediaPipeRuntimeFactory
import com.omnix.assistant.agent.location.LocationProvider
import com.omnix.assistant.agent.location.SystemLocationProvider
import com.omnix.assistant.agent.translator.LlmTranslationProvider
import com.omnix.assistant.agent.translator.TranslationProvider
import com.omnix.assistant.agent.weather.OpenMeteoWeatherProvider
import com.omnix.assistant.agent.weather.WeatherProvider
import com.omnix.assistant.agent.memory.context.BasicMorphologyResolver
import com.omnix.assistant.agent.memory.context.MorphologyResolver
import com.omnix.assistant.agent.memory.context.ReferenceResolver
import com.omnix.assistant.agent.memory.dao.*
import com.omnix.assistant.agent.tools.accessibility.ScreenReaderTool
import com.omnix.assistant.agent.tools.accessibility.UiClickTool
import com.omnix.assistant.agent.tools.accessibility.UiTypeTextTool
import com.omnix.assistant.agent.tools.communication.*
import com.omnix.assistant.agent.tools.device.*
import com.omnix.assistant.agent.tools.intelligence.ForgetMemoryTool
import com.omnix.assistant.agent.tools.intelligence.RecallMemoryTool
import com.omnix.assistant.agent.tools.intelligence.RememberFactTool
import com.omnix.assistant.agent.tools.intelligence.TranslateTool
import com.omnix.assistant.agent.tools.health.ActivityTool
import com.omnix.assistant.agent.tools.health.HeartRateTool
import com.omnix.assistant.agent.tools.health.SleepTool
import com.omnix.assistant.agent.tools.health.StepsTool
import com.omnix.assistant.agent.tools.health.WearOsTool
import com.omnix.assistant.agent.tools.intelligence.VisionTool
import com.omnix.assistant.agent.tools.intelligence.WeatherTool
import com.omnix.assistant.agent.tools.intelligence.WebSearchTool
import com.omnix.assistant.agent.tools.location.LocationNavigationTool
import com.omnix.assistant.agent.tools.media.MediaControlTool
import com.omnix.assistant.agent.tools.productivity.AlarmTimerTool
import com.omnix.assistant.agent.tools.productivity.CalendarTool
import com.omnix.assistant.agent.tools.productivity.ClipboardTool
import com.omnix.assistant.agent.tools.productivity.CreateAutomationTool
import com.omnix.assistant.agent.tools.productivity.EarBriefingTool
import com.omnix.assistant.agent.tools.system.*
import com.omnix.assistant.ai.AIClient
import com.omnix.assistant.ai.OmnixApiAiClient
import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import com.omnix.assistant.core.dispatcher.DefaultCoroutineDispatchers
import com.omnix.assistant.core.license.HttpLicenseServerValidator
import com.omnix.assistant.core.license.LicenseCodeValidator
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.LicenseManagerImpl
import com.omnix.assistant.core.license.LicenseServerValidator
import com.omnix.assistant.core.network.LiveNetworkMonitor
import com.omnix.assistant.core.network.NetworkMonitor
import com.omnix.assistant.core.security.SecurityManager
import com.omnix.assistant.core.security.SecurityManagerImpl
import com.omnix.assistant.data.local.OmnixDatabase
import com.omnix.assistant.data.local.OmnixDatabaseFactory
import com.omnix.assistant.data.local.dao.MessageDao
import com.omnix.assistant.data.remote.interceptor.AuthInterceptor
import com.omnix.assistant.data.repository.AIRepositoryImpl
import com.omnix.assistant.data.repository.MessageRepositoryImpl
import com.omnix.assistant.data.repository.SettingsRepositoryImpl
import com.omnix.assistant.domain.repository.AIRepository
import com.omnix.assistant.domain.repository.MessageRepository
import com.omnix.assistant.domain.repository.SettingsRepository
import com.omnix.assistant.voice.wakeword.NeuralWakeWordEngine
import com.omnix.assistant.voice.wakeword.WakeWordEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @Singleton
    fun provideCoroutineDispatchers(): CoroutineDispatchers = DefaultCoroutineDispatchers()

    @Provides
    @Singleton
    fun provideMorphologyResolver(): MorphologyResolver = BasicMorphologyResolver()

    @Provides
    @Singleton
    fun provideReferenceResolver(morphology: MorphologyResolver): ReferenceResolver =
        ReferenceResolver(morphology)

    @Provides
    @Singleton
    fun provideLicenseCodeValidator(): LicenseCodeValidator = LicenseCodeValidator()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityAndNetworkBindingModule {
    @Binds
    @Singleton
    abstract fun bindActionPolicySettingsProvider(
        impl: com.omnix.assistant.agent.policy.DefaultActionPolicySettingsProvider
    ): com.omnix.assistant.agent.policy.ActionPolicySettingsProvider

    @Binds
    @Singleton
    abstract fun bindSecurityManager(impl: SecurityManagerImpl): SecurityManager

    @Binds
    @Singleton
    abstract fun bindLicenseManager(impl: LicenseManagerImpl): LicenseManager

    @Binds
    @Singleton
    abstract fun bindLicenseServerValidator(impl: HttpLicenseServerValidator): LicenseServerValidator

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: LiveNetworkMonitor): NetworkMonitor

    @Binds
    @Singleton
    abstract fun bindAIClient(impl: OmnixApiAiClient): AIClient

    @Binds
    @Singleton
    abstract fun bindWakeWordEngine(impl: NeuralWakeWordEngine): WakeWordEngine

    @Binds
    @Singleton
    abstract fun bindWeatherProvider(impl: OpenMeteoWeatherProvider): WeatherProvider

    @Binds
    @Singleton
    abstract fun bindCapabilityChecker(impl: DeviceCapabilityRegistry): CapabilityChecker

    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: SystemLocationProvider): LocationProvider

    @Multibinds
    abstract fun bindToolsSet(): Set<OmniTool>

    @Multibinds
    abstract fun bindTranslationProviders(): Set<TranslationProvider>

    // Провайдеры перевода: local-first — on-device Gemma (если пользователь
    // установил модель) пробуется первым, облако — fallback (длинный текст,
    // модель не готова). Оба реально работающие, заглушек нет.
    @Binds @IntoSet abstract fun bindLocalLlmTranslationProvider(provider: com.omnix.assistant.agent.translator.LocalLlmTranslationProvider): TranslationProvider
    @Binds @IntoSet abstract fun bindLlmTranslationProvider(provider: LlmTranslationProvider): TranslationProvider

    // 1. Системные инструменты (System)
    @Binds @IntoSet abstract fun bindGetDeviceInfoTool(tool: GetDeviceInfoTool): OmniTool
    @Binds @IntoSet abstract fun bindGetBatteryTool(tool: GetBatteryTool): OmniTool
    @Binds @IntoSet abstract fun bindGetTimeTool(tool: GetTimeTool): OmniTool
    @Binds @IntoSet abstract fun bindGetNetworkStatusTool(tool: GetNetworkStatusTool): OmniTool

    // 2. Управление устройством (Device & Media)
    @Binds @IntoSet abstract fun bindOpenAppTool(tool: OpenAppTool): OmniTool
    @Binds @IntoSet abstract fun bindSetVolumeTool(tool: SetVolumeTool): OmniTool
    @Binds @IntoSet abstract fun bindSetBrightnessTool(tool: SetBrightnessTool): OmniTool
    @Binds @IntoSet abstract fun bindFlashlightTool(tool: FlashlightTool): OmniTool
    @Binds @IntoSet abstract fun bindBluetoothTool(tool: BluetoothTool): OmniTool
    @Binds @IntoSet abstract fun bindWifiTool(tool: WifiTool): OmniTool
    @Binds @IntoSet abstract fun bindDoNotDisturbTool(tool: DoNotDisturbTool): OmniTool
    @Binds @IntoSet abstract fun bindScreenshotTool(tool: ScreenshotTool): OmniTool
    @Binds @IntoSet abstract fun bindMediaControlTool(tool: MediaControlTool): OmniTool

    // 3. Связь и коммуникации (Communication)
    @Binds @IntoSet abstract fun bindCallTool(tool: CallTool): OmniTool
    @Binds @IntoSet abstract fun bindSmsTool(tool: SmsTool): OmniTool
    @Binds @IntoSet abstract fun bindContactsTool(tool: ContactsTool): OmniTool
    @Binds @IntoSet abstract fun bindShareTool(tool: ShareTool): OmniTool
    @Binds @IntoSet abstract fun bindTelegramTool(tool: TelegramTool): OmniTool

    // 4. Продуктивность и задачи (Productivity)
    @Binds @IntoSet abstract fun bindAlarmTimerTool(tool: AlarmTimerTool): OmniTool
    @Binds @IntoSet abstract fun bindCalendarTool(tool: CalendarTool): OmniTool
    @Binds @IntoSet abstract fun bindClipboardTool(tool: ClipboardTool): OmniTool
    @Binds @IntoSet abstract fun bindCreateAutomationTool(tool: CreateAutomationTool): OmniTool
    @Binds @IntoSet abstract fun bindEarBriefingTool(tool: EarBriefingTool): OmniTool

    // 5. Локация и навигация (Location)
    @Binds @IntoSet abstract fun bindLocationNavigationTool(tool: LocationNavigationTool): OmniTool

    // 6. Интеллект, память и поиск (Intelligence & Memory 2.0)
    @Binds @IntoSet abstract fun bindRememberFactTool(tool: RememberFactTool): OmniTool
    @Binds @IntoSet abstract fun bindRecallMemoryTool(tool: RecallMemoryTool): OmniTool
    @Binds @IntoSet abstract fun bindForgetMemoryTool(tool: ForgetMemoryTool): OmniTool
    @Binds @IntoSet abstract fun bindWebSearchTool(tool: WebSearchTool): OmniTool
    @Binds @IntoSet abstract fun bindTranslateTool(tool: TranslateTool): OmniTool
    @Binds @IntoSet abstract fun bindWeatherTool(tool: WeatherTool): OmniTool
    @Binds @IntoSet abstract fun bindVisionTool(tool: VisionTool): OmniTool

    // 8. Здоровье (Wear OS / Health — честные заглушки до появления источников данных)
    @Binds @IntoSet abstract fun bindWearOsTool(tool: WearOsTool): OmniTool
    @Binds @IntoSet abstract fun bindHeartRateTool(tool: HeartRateTool): OmniTool
    @Binds @IntoSet abstract fun bindStepsTool(tool: StepsTool): OmniTool
    @Binds @IntoSet abstract fun bindSleepTool(tool: SleepTool): OmniTool
    @Binds @IntoSet abstract fun bindActivityTool(tool: ActivityTool): OmniTool

    // 7. Спец. возможности и UI управление (Accessibility)
    @Binds @IntoSet abstract fun bindScreenReaderTool(tool: ScreenReaderTool): OmniTool
    @Binds @IntoSet abstract fun bindUiClickTool(tool: UiClickTool): OmniTool
    @Binds @IntoSet abstract fun bindUiTypeTextTool(tool: UiTypeTextTool): OmniTool
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideOmnixDatabase(
        @ApplicationContext context: Context
    ): OmnixDatabase {
        return OmnixDatabaseFactory.create(context)
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: OmnixDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideMemoryDao(database: OmnixDatabase): MemoryDao = database.memoryDao()

    @Provides
    @Singleton
    fun provideFactDao(database: OmnixDatabase): FactDao = database.factDao()

    @Provides
    @Singleton
    fun providePreferenceDao(database: OmnixDatabase): PreferenceDao = database.preferenceDao()

    @Provides
    @Singleton
    fun provideProcedureDao(database: OmnixDatabase): ProcedureDao = database.procedureDao()

    @Provides
    @Singleton
    fun provideAutomationDao(database: OmnixDatabase): AutomationDao = database.automationDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("x-goog-api-key")
            redactHeader("api-key")
            redactHeader("X-Api-Key")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(AppConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConstants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindAIRepository(impl: AIRepositoryImpl): AIRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}

/**
 * Execution Decision Engine v0.2 (Этап 1).
 *
 * Сам [com.omnix.assistant.agent.decision.ExecutionDecisionEngine] получает
 * зависимости через @Inject-конструктор и является @Singleton — здесь
 * связываются только порты с их адаптерами над существующими компонентами
 * (WorkflowExecutor, AIRepository, CognitivePlanner + AgentCognitiveLoop).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExecutionDecisionModule {
    @Binds
    @Singleton
    abstract fun bindLocalAiExecutor(impl: CompositeLocalAiExecutor): LocalAiExecutor

    @Binds
    @Singleton
    abstract fun bindCloudAiExecutor(impl: RepositoryCloudAiExecutor): CloudAiExecutor

    @Binds
    @Singleton
    abstract fun bindAgentExecutor(impl: CognitiveAgentExecutor): AgentExecutor

    companion object {
        /**
         * TODO: deviceConfidenceThreshold требует калибровки на реальных логах.
         * Значение по умолчанию подобрано так, чтобы сохранить текущее
         * поведение FastCommandRouter (см. ExecutionDecisionConfig).
         */
        @Provides
        @Singleton
        fun provideExecutionDecisionConfig(): ExecutionDecisionConfig = ExecutionDecisionConfig()
    }
}

/**
 * Local AI (Этап 2) — on-device LLM как execution backend.
 *
 * Модель НЕ грузится при старте: [MediaPipeModelManager] инициализируется
 * лениво, при первом обращении к локальному пути. Все компоненты —
 * @Singleton, чтобы нативный движок существовал в единственном экземпляре.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalAiModule {
    @Binds
    @Singleton
    abstract fun bindLocalAi(impl: OnDeviceLocalAi): LocalAi

    @Binds
    @Singleton
    abstract fun bindLocalPromptBuilder(impl: OmniLocalPromptBuilder): LocalPromptBuilder

    @Binds
    @Singleton
    abstract fun bindLocalModelManager(impl: MediaPipeModelManager): LocalModelManager

    @Binds
    @Singleton
    abstract fun bindPackModelLocator(
        impl: AssetManagerPackModelLocator
    ): PackModelLocator

    @Binds
    @Singleton
    abstract fun bindMediaPipeRuntimeFactory(
        impl: DefaultMediaPipeRuntimeFactory
    ): MediaPipeRuntimeFactory

    @Binds
    @Singleton
    abstract fun bindModelDownloader(
        impl: DownloadManagerModelDownloader
    ): ModelDownloader

    companion object {
        /**
         * Спецификация локальной модели вынесена в DI: заменить модель можно
         * здесь, не трогая runtime и decision engine.
         */
        @Provides
        @Singleton
        fun provideLocalModelSpec(): LocalModelSpec = LocalModelSpec.QWEN2_5_0_5B_INSTRUCT_Q8
    }
}
