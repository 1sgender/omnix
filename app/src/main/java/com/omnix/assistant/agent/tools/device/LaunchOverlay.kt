package com.omnix.assistant.agent.tools.device

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.delay

/**
 * Маленькая плашка «Открываю …» поверх экрана (TYPE_APPLICATION_OVERLAY).
 *
 * Зачем: начиная с Android 15 одно разрешение «Поверх других приложений» не даёт
 * права запускать чужие Activity из фона, нужно ВИДИМОЕ окно-оверлей
 * (developer.android.com/about/versions/15/behavior-changes-15, Secured
 * background activity launches). Плашка заодно честный UX: пользователь видит,
 * что ассистент делает. Окно не берёт фокус и не перехватывает касания.
 * Требует выданного [android.provider.Settings.canDrawOverlays]; вызывать на Main.
 */
internal class LaunchOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: View? = null

    /** Показывает плашку и ждёт, пока окно реально стало видимым. false: добавить не удалось. */
    suspend fun showAndAwaitVisible(text: String): Boolean {
        val label = TextView(appContext).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = TEXT_SIZE_SP
            val pad = (PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                setColor(BACKGROUND_COLOR)
                cornerRadius = CORNER_DP * resources.displayMetrics.density
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_OFFSET_DP * appContext.resources.displayMetrics.density).toInt()
        }
        return try {
            windowManager.addView(label, params)
            view = label
            var waited = 0L
            while (waited < VISIBLE_TIMEOUT_MS &&
                !(label.isAttachedToWindow && label.windowVisibility == View.VISIBLE)
            ) {
                delay(POLL_MS)
                waited += POLL_MS
            }
            // Окну нужен кадр, чтобы система сочла его видимым.
            delay(SETTLE_MS)
            label.isAttachedToWindow && label.windowVisibility == View.VISIBLE
        } catch (_: RuntimeException) {
            // BadTokenException / SecurityException: разрешение отозвали по пути.
            hide()
            false
        }
    }

    /** Убирает плашку после запуска; не сразу, чтобы она пережила переключение окон. */
    fun hideLater(delayMs: Long = HIDE_DELAY_MS) {
        mainHandler.postDelayed({ hide() }, delayMs)
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager.removeViewImmediate(current) }
    }

    private companion object {
        const val TEXT_SIZE_SP = 16f
        const val PADDING_DP = 10
        const val CORNER_DP = 20f
        const val TOP_OFFSET_DP = 48
        val BACKGROUND_COLOR: Int = 0xE6202124L.toInt()
        const val POLL_MS = 50L
        const val SETTLE_MS = 150L
        const val VISIBLE_TIMEOUT_MS = 800L
        const val HIDE_DELAY_MS = 2_000L
    }
}
