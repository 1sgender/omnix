package com.omnix.assistant.presentation.diagnostics

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.core.crash.CrashCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Состояние экрана OMNIX DIAGNOSTICS (§21 ТЗ).
 *
 * При открытии автоматически запускает полный прогон; RUN FULL TEST
 * повторяет его. Проверки идут строго последовательно (микрофон и сеть
 * не любят параллельных проб), прогресс — счётчик готовых.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: DiagnosticsEngine
) : ViewModel() {

    private val _rows = MutableStateFlow(
        DiagnosticCheckId.entries.map { DiagnosticResult(it, DiagnosticStatus.PENDING) }
    )
    val rows: StateFlow<List<DiagnosticResult>> = _rows.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _completed = MutableStateFlow(0)
    val completed: StateFlow<Int> = _completed.asStateFlow()

    val total: Int = DiagnosticCheckId.entries.size

    init {
        runFullTest()
    }

    /** Последовательный прогон всех 12 проверок с живым прогрессом. */
    fun runFullTest() {
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            _completed.value = 0
            try {
                for (id in DiagnosticCheckId.entries) {
                    setStatus(id, DiagnosticStatus.RUNNING)
                    val result = engine.runCheck(id)
                    _rows.update { list ->
                        list.map { if (it.id == id) result else it }
                    }
                    _completed.value += 1
                }
            } finally {
                _running.value = false
            }
        }
    }

    /** Текст для EXPORT REPORT (ACTION_SEND, plain text). */
    fun buildReportText(): String =
        DiagnosticsReport.build(
            results = _rows.value,
            build = DiagnosticsReport.BuildInfo(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                deviceModel = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE ?: "?",
                sdkInt = Build.VERSION.SDK_INT,
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            ),
            // Java-краши, записанные CrashCapture (нативные — см. ModelInitCrashGuard).
            crashes = CrashCapture.latestReports(appContext)
        )

    private fun setStatus(id: DiagnosticCheckId, status: DiagnosticStatus) {
        _rows.update { list ->
            list.map { if (it.id == id) it.copy(status = status) else it }
        }
    }
}
