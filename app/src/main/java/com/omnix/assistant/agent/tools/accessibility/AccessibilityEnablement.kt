package com.omnix.assistant.agent.tools.accessibility

import java.util.Locale

/**
 * Состояние службы специальных возможностей OMNIX и подсказка, что делать
 * пользователю: чистая логика без Android, чтобы её проверять юнит-тестами.
 *
 * Служба необязательная: без неё работают запасные пути (уведомление, тап по
 * нему открывает приложение). Поэтому здесь только определяем состояние и
 * выбираем шаг мастера, ничего не навязываем.
 *
 * Про «ограниченные настройки» (Restricted settings, Android 13+): систему
 * не спросить напрямую, заблокировано ли включение службы именно сейчас
 * (публичного API нет). Поэтому честно говорим «вероятно»: приложение не из
 * магазина, SDK 33+, служба выключена. Это эвристика, а не факт.
 */
object AccessibilityEnablement {

    /** С Android 13 (API 33) включение служб у приложений не из магазина блокируется. */
    const val FIRST_RESTRICTED_SDK = 33

    /**
     * Установщики, которые Android считает магазином. Список заведомо неполный
     * (прошивки добавляют свои магазины), поэтому «не из списка» даёт только
     * «вероятно ограничено», а не категоричный вывод.
     */
    private val STORE_INSTALLERS = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.xiaomi.mipicks",
        "com.amazon.venezia"
    )

    enum class Status {
        /** Служба включена в системных настройках. */
        ENABLED,

        /** Выключена, и включение, вероятно, заблокировано «ограниченными настройками». */
        DISABLED_RESTRICTED_LIKELY,

        /** Выключена, ограничения, по всем признакам, нет: достаточно включить в списке. */
        DISABLED
    }

    /** Какой шаг мастера сейчас актуален. */
    enum class Step { UNLOCK_RESTRICTED, ENABLE_SERVICE, DONE }

    /** Производитель прошивки: от него зависят слова в подсказке. */
    enum class Oem { SAMSUNG, PIXEL, XIAOMI, OTHER }

    /**
     * Включена ли служба. [enabledServices] это содержимое
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES: компоненты через «:»,
     * в полной ("pkg/pkg.Cls") или краткой ("pkg/.Cls") записи.
     */
    fun isServiceEnabled(enabledServices: String?, packageName: String, serviceClass: String): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        val wanted = normalize(packageName, serviceClass)
        return enabledServices.split(':').any { entry ->
            val trimmed = entry.trim()
            val slash = trimmed.indexOf('/')
            slash > 0 && normalize(trimmed.substring(0, slash), trimmed.substring(slash + 1)) == wanted
        }
    }

    private fun normalize(packageName: String, className: String): String {
        val pkg = packageName.trim()
        val cls = className.trim().let { if (it.startsWith(".")) pkg + it else it }
        return (pkg + "/" + cls).lowercase(Locale.ROOT)
    }

    /** Установлено ли приложение из магазина (по пакету установщика; null = неизвестно/файл). */
    fun isFromStore(installerPackage: String?): Boolean =
        installerPackage != null && installerPackage in STORE_INSTALLERS

    fun status(
        enabledServices: String?,
        packageName: String,
        serviceClass: String,
        sdkInt: Int,
        installerPackage: String?
    ): Status = when {
        isServiceEnabled(enabledServices, packageName, serviceClass) -> Status.ENABLED
        sdkInt >= FIRST_RESTRICTED_SDK && !isFromStore(installerPackage) ->
            Status.DISABLED_RESTRICTED_LIKELY
        else -> Status.DISABLED
    }

    fun currentStep(status: Status): Step = when (status) {
        Status.ENABLED -> Step.DONE
        Status.DISABLED_RESTRICTED_LIKELY -> Step.UNLOCK_RESTRICTED
        Status.DISABLED -> Step.ENABLE_SERVICE
    }

    /**
     * Нужно ли дописывать к ответу о заблокированном запуске подсказку про
     * службу: только пока она не включена. Включённой подсказка не нужна.
     */
    fun shouldSuggestInBlockedLaunch(status: Status): Boolean = status != Status.ENABLED

    fun oemFor(manufacturer: String?): Oem {
        val m = manufacturer.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            m == "samsung" -> Oem.SAMSUNG
            m == "google" -> Oem.PIXEL
            m == "xiaomi" || m == "redmi" || m == "poco" -> Oem.XIAOMI
            else -> Oem.OTHER
        }
    }
}
