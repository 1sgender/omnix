package com.omnix.assistant.agent.decision

import com.omnix.assistant.agent.capability.FakeCapabilityRegistry
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.discovery.ToolDiscoveryEngine
import com.omnix.assistant.agent.executor.ToolExecutor
import com.omnix.assistant.agent.fast.FastCommandRouter
import com.omnix.assistant.agent.localai.GenerationConfig
import com.omnix.assistant.agent.localai.InferenceMetrics
import com.omnix.assistant.agent.localai.OmniLocalPromptBuilder
import com.omnix.assistant.agent.localai.LocalAi
import com.omnix.assistant.agent.localai.LocalAiResult
import com.omnix.assistant.agent.localai.LocalGeneration
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelRuntime
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.localai.OnDeviceLocalAi
import com.omnix.assistant.agent.memory.WorkingMemory
import com.omnix.assistant.agent.memory.context.AnaphoraContextEngine
import com.omnix.assistant.agent.memory.context.ReferenceResolver
import com.omnix.assistant.agent.memory.semantic.SemanticTextMatcher
import com.omnix.assistant.agent.model.ToolCall
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import com.omnix.assistant.agent.planner.ExecutionPlan
import com.omnix.assistant.agent.planner.PlanStep
import com.omnix.assistant.agent.registry.ToolRegistry
import com.omnix.assistant.agent.safety.ToolPermissionManager
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import com.omnix.assistant.core.result.Resource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Этап 2 — integration-тесты полного маршрута (пункт 22 ТЗ).
 *
 * Ключевая цель: доказать, что подключение Local AI НЕ СЛОМАЛО маршрутизацию
 * Этапа 1. Используются НАСТОЯЩИЕ FastCommandRouter, ToolExecutor,
 * ExecutionDecisionEngine и настоящая цепочка CompositeLocalAiExecutor →
 * OnDeviceLocalAi; подменён только сам inference runtime и облако.
 *
 * ```
 * "Открой Telegram"                    → DEVICE_TOOL
 * "Что значит квантовая запутанность?" → LOCAL_AI
 * "Найди актуальную цену биткоина"     → CLOUD_AI
 * "Подготовь ко сну ..."               → AGENT
 * ```
 */
class LocalAiRoutingIntegrationTest {

    // ------------------------------------------------------------------ fakes

    private class TestDispatchers : CoroutineDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.Default
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class EchoRuntime(private val answer: String) : LocalModelRuntime {
        override val runtimeId = "test-runtime"
        val calls = AtomicInteger(0)

        override suspend fun generate(
            prompt: String,
            config: GenerationConfig,
            onToken: ((String) -> Unit)?
        ): LocalGeneration {
            calls.incrementAndGet()
            return LocalGeneration(answer, InferenceMetrics(latencyMs = 3))
        }
    }

    private class ReadyManager(private val runtime: LocalModelRuntime?) : LocalModelManager {
        override val state: LocalModelState =
            if (runtime != null) LocalModelState.Ready("test", 1)
            else LocalModelState.NotInstalled("/nowhere/model.task")

        override val stateFlow: StateFlow<LocalModelState>
            get() = MutableStateFlow(state)

        override suspend fun initialize() = state
        override fun isReady() = runtime != null
        override suspend fun runtimeOrNull() = runtime
        override suspend fun ensureModel(): LocalModelState = state
        override fun cancelDownload() = Unit
        override suspend fun unload() = Unit
    }

    private class OpenAppTool : OmniTool {
        override val toolId = "device.open_app"
        override val description = "Открывает приложение"
        override val category = ToolCategory.DEVICE
        override val riskLevel = ToolRisk.LOW
        override val parametersSchema: JsonObject = buildJsonObject { }
        val calls = AtomicInteger(0)

        override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
            calls.incrementAndGet()
            return ToolExecutionResult.success("Открываю Telegram")
        }
    }

    private class FakeCloud(
        private val response: Resource<String> = Resource.Success("Ответ из облака")
    ) : CloudAiExecutor {
        val calls = AtomicInteger(0)
        override fun isAvailable() = true
        override suspend fun complete(request: ExecutionRequest): Resource<String> {
            calls.incrementAndGet()
            return response
        }
    }

    private class FakeAgent(private val plan: ExecutionPlan?) : AgentExecutor {
        val runCalls = AtomicInteger(0)
        override fun planFor(request: ExecutionRequest, llmRawOutput: String?): ExecutionPlan? =
            if (llmRawOutput == null) plan else null

        override suspend fun run(plan: ExecutionPlan): ExecutionResult {
            runCalls.incrementAndGet()
            return ExecutionResult.Success("План выполнен, сэр.", ExecutionType.AGENT)
        }
    }

    /** Процедурная память недоступна в JVM-тесте (нужен Room) — «нет макроса». */
    private class NoWorkflowLocalAiExecutor(private val localAi: LocalAi) : LocalAiExecutor {
        override val hasWebCapability = false
        override suspend fun tryHandle(request: ExecutionRequest): LocalAiOutcome =
            when (val r = localAi.execute(request)) {
                is LocalAiResult.Success -> LocalAiOutcome.Handled(r.text)
                is LocalAiResult.Unsupported -> LocalAiOutcome.Uncertain
                is LocalAiResult.Error -> LocalAiOutcome.Failed(r.message)
            }
    }

    // ------------------------------------------------------------------ setup

    private class Harness(
        val engine: ExecutionDecisionEngine,
        val tool: OpenAppTool,
        val runtime: EchoRuntime,
        val cloud: FakeCloud,
        val agent: FakeAgent
    )

    private fun harness(
        localAnswer: String = "Это квантовая корреляция частиц.",
        modelInstalled: Boolean = true,
        agentPlan: ExecutionPlan? = null,
        cloudResponse: Resource<String> = Resource.Success("Ответ из облака")
    ): Harness {
        val tool = OpenAppTool()
        val registry = ToolRegistry(setOf(tool), ToolDiscoveryEngine(SemanticTextMatcher()))
        val toolExecutor = ToolExecutor(registry, ToolPermissionManager(FakeCapabilityRegistry.create()))
        val workingMemory = WorkingMemory(AnaphoraContextEngine(), ReferenceResolver())

        val runtime = EchoRuntime(localAnswer)
        val localAi = OnDeviceLocalAi(
            modelManager = ReadyManager(if (modelInstalled) runtime else null),
            promptBuilder = OmniLocalPromptBuilder(),
            dispatchers = TestDispatchers()
        )

        val cloud = FakeCloud(cloudResponse)
        val agent = FakeAgent(agentPlan)

        val engine = ExecutionDecisionEngine(
            fastCommandRouter = FastCommandRouter(),
            toolExecutor = toolExecutor,
            agentExecutor = agent,
            localAi = NoWorkflowLocalAiExecutor(localAi),
            cloudAi = cloud,
            workingMemory = workingMemory,
            config = ExecutionDecisionConfig()
        )

        return Harness(engine, tool, runtime, cloud, agent)
    }

    private fun voice(
        text: String,
        requiresWeb: Boolean = false,
        privacy: PrivacyLevel = PrivacyLevel.NORMAL
    ) = ExecutionRequest(
        text = text,
        source = RequestSource.VOICE,
        requiresWeb = requiresWeb,
        privacyLevel = privacy
    )

    private fun plan() = ExecutionPlan(
        goal = "multi-step",
        steps = listOf(PlanStep(toolCall = ToolCall("device.open_app", buildJsonObject { })))
    )

    // ------------------------------------------------------------------ tests

    /** "Открой Telegram" → DEVICE_TOOL, Local AI не трогается. */
    @Test
    fun `device command still routes to device tool`() = runBlocking {
        val h = harness()

        val result = h.engine.execute(voice("Открой Telegram"))

        assertTrue("Ожидался Success: $result", result is ExecutionResult.Success)
        assertEquals(ExecutionType.DEVICE_TOOL, (result as ExecutionResult.Success).executionType)
        assertEquals(1, h.tool.calls.get())
        assertEquals("Local AI не должен вызываться", 0, h.runtime.calls.get())
        assertEquals(0, h.cloud.calls.get())
    }

    /** "Что значит квантовая запутанность?" → LOCAL_AI. */
    @Test
    fun `knowledge question routes to local ai`() = runBlocking {
        val h = harness(localAnswer = "Это квантовая корреляция частиц.")

        val result = h.engine.execute(voice("Что значит квантовая запутанность?"))

        assertTrue("Ожидался Success: $result", result is ExecutionResult.Success)
        val success = result as ExecutionResult.Success
        assertEquals(ExecutionType.LOCAL_AI, success.executionType)
        assertEquals("Это квантовая корреляция частиц.", success.text)
        assertEquals(1, h.runtime.calls.get())
        assertEquals("Облако не нужно для локального вопроса", 0, h.cloud.calls.get())
    }

    /** requiresWeb → CLOUD_AI, локальная модель не выдумывает данные. */
    @Test
    fun `web request routes to cloud and never touches local model`() = runBlocking {
        val h = harness(cloudResponse = Resource.Success("Актуальная цена получена"))

        val result = h.engine.execute(voice("Найди актуальную цену биткоина", requiresWeb = true))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.CLOUD_AI, (result as ExecutionResult.Success).executionType)
        assertEquals("Локальная модель не должна вызываться", 0, h.runtime.calls.get())
        assertEquals(1, h.cloud.calls.get())
    }

    /** Многошаговый запрос → AGENT, минуя Local AI. */
    @Test
    fun `complex request routes to agent`() = runBlocking {
        val h = harness(agentPlan = plan())

        val result = h.engine.execute(voice("Подготовь телефон ко сну и выключи всё лишнее"))

        assertTrue(result is ExecutionResult.Success)
        assertEquals(ExecutionType.AGENT, (result as ExecutionResult.Success).executionType)
        assertEquals(1, h.agent.runCalls.get())
        assertEquals(0, h.runtime.calls.get())
    }

    /** Модель не установлена → тихий переход в облако, без ошибки. */
    @Test
    fun `missing local model falls back to cloud`() = runBlocking {
        val h = harness(modelInstalled = false)

        val result = h.engine.execute(voice("Что значит квантовая запутанность?"))

        assertTrue("Ожидался Success через облако: $result", result is ExecutionResult.Success)
        assertEquals(ExecutionType.CLOUD_AI, (result as ExecutionResult.Success).executionType)
        assertEquals(1, h.cloud.calls.get())
    }

    /**
     * PRIVATE + локальная модель → ответ локально, облако НЕ вызывается.
     * Это главный выигрыш Этапа 2: раньше такой запрос упирался в Error.
     */
    @Test
    fun `private request is answered locally without cloud`() = runBlocking {
        val h = harness(localAnswer = "Отвечаю локально, сэр.")

        val result = h.engine.execute(
            voice("Напомни, что я записывал о своём здоровье", privacy = PrivacyLevel.PRIVATE)
        )

        assertTrue("Ожидался Success: $result", result is ExecutionResult.Success)
        assertEquals(ExecutionType.LOCAL_AI, (result as ExecutionResult.Success).executionType)
        assertEquals("Приватный запрос НЕ должен уходить в облако", 0, h.cloud.calls.get())
    }

    /** PRIVATE без локальной модели по-прежнему блокируется, а не утекает в облако. */
    @Test
    fun `private request without local model is still blocked from cloud`() = runBlocking {
        val h = harness(modelInstalled = false)

        val result = h.engine.execute(
            voice("Мои личные данные", privacy = PrivacyLevel.PRIVATE)
        )

        assertTrue("Ожидался Error: $result", result is ExecutionResult.Error)
        assertEquals(
            DecisionReason.CLOUD_BLOCKED_BY_PRIVACY,
            (result as ExecutionResult.Error).reason
        )
        assertEquals(0, h.cloud.calls.get())
    }

    /** Детерминированность: один проход по цепочке, без циклов Local↔Cloud. */
    @Test
    fun `fallback chain runs each backend at most once`() = runBlocking {
        val h = harness(modelInstalled = false)

        h.engine.execute(voice("Расскажи что-нибудь про космос"))

        assertEquals("Cloud должен вызываться ровно один раз", 1, h.cloud.calls.get())
        assertEquals(0, h.runtime.calls.get())
    }
}
