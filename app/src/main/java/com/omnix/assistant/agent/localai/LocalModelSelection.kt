package com.omnix.assistant.agent.localai

import android.app.ActivityManager
import android.content.Context

/**
 * Модель на телефоне по объёму памяти: 1.5B на телефонах с ~8 ГБ и больше,
 * 0.5B на остальных (см. [LocalModelSpec.forTotalRam]). Одна точка для DI и
 * экранов, чтобы настройки показывали ту же модель, что реально качается.
 */
object LocalModelSelection {

    fun forDevice(context: Context): LocalModelSpec {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return LocalModelSpec.QWEN2_5_0_5B_INSTRUCT_Q8
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return LocalModelSpec.forTotalRam(info.totalMem / BYTES_PER_MB)
    }

    private const val BYTES_PER_MB = 1024L * 1024L
}
