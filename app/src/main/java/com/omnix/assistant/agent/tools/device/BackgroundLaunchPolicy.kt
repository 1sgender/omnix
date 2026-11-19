package com.omnix.assistant.agent.tools.device

/**
 * Разрешит ли Android запустить чужую Activity прямо сейчас: чистая логика.
 *
 * С Android 10 приложение без видимого окна не может открывать другие
 * приложения (Background Activity Launch): startActivity не бросает ошибку,
 * запуск молча отбрасывается. Исключения, которыми мы можем воспользоваться:
 * видимое окно OMNIX либо разрешение «Поверх других приложений».
 */
object BackgroundLaunchPolicy {
    private const val FIRST_RESTRICTED_SDK = 29

    fun isBlocked(sdkInt: Int, appVisible: Boolean, canDrawOverlays: Boolean): Boolean =
        sdkInt >= FIRST_RESTRICTED_SDK && !appVisible && !canDrawOverlays
}
