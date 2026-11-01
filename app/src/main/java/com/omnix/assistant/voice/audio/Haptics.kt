package com.omnix.assistant.voice.audio

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Лёгкий вибро-отклик на «Омни» и отправку сообщения. Включатель живёт в
 * SharedPreferences (чтобы читаться синхронно из любого потока); меняется
 * в «Я → Голос» через [setEnabled].
 */
object Haptics {
    private const val TAG = "Haptics"
    private const val PREFS = "omnix_haptics"
    private const val KEY_ENABLED = "enabled"
    private const val TICK_MS = 22L
    private const val TICK_AMPLITUDE = 110

    fun isEnabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    } catch (e: Exception) {
        true
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Короткий мягкий «тик»; ничего не делает, если выключено или нет вибромотора. */
    fun tick(context: Context) {
        if (!isEnabled(context)) return
        try {
            val vibrator = context.applicationContext.getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(TICK_MS, TICK_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "tick: не удалось вибрировать", e)
        }
    }
}
