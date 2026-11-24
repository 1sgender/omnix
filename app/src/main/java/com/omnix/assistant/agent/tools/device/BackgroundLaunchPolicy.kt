package com.omnix.assistant.agent.tools.device

/**
 * Каким законным путём запустить чужую Activity, когда экрана OMNIX нет:
 * чистая логика без Android.
 *
 * С Android 10 приложение без видимого окна не может открывать другие
 * приложения (Background Activity Launch): startActivity не бросает ошибку,
 * запуск молча отбрасывается. С Android 15 исключение «SYSTEM_ALERT_WINDOW»
 * сужено: одного разрешения мало, нужно ещё ВИДИМОЕ окно TYPE_APPLICATION_OVERLAY
 * (https://developer.android.com/about/versions/15/behavior-changes-15,
 * раздел Secured background activity launches: «block non-visible windows
 * from being considered for background activity launches»).
 *
 * Поэтому маршрут строится как список попыток по убыванию дешевизны:
 *  1. [Route.DIRECT]: есть видимое окно OMNIX (или Android 9-) — запускаем сразу.
 *  2. [Route.ACCESSIBILITY]: подключена служба доступности OMNIX. Сервис, к
 *     которому привязана сама система, имеет право стартовать Activity из фона
 *     (по исходникам AOSP; поведение зависит от прошивки, поэтому результат
 *     проверяем по активному пакету, а не верим на слово).
 *  3. [Route.OVERLAY]: выдано «Поверх других приложений»: показываем
 *     маленькое окно «Открываю…» и стартуем из-под него.
 * Пустой план = законного пути нет: честно говорим об этом и даём уведомление.
 */
object BackgroundLaunchPolicy {
    private const val FIRST_RESTRICTED_SDK = 29

    enum class Route { DIRECT, ACCESSIBILITY, OVERLAY }

    /** Удалось ли убедиться, что нужное приложение действительно вышло на экран. */
    enum class Verification { CONFIRMED, NOT_CONFIRMED, UNKNOWN }

    enum class Step { DONE, TRY_NEXT }

    fun plan(
        sdkInt: Int,
        appVisible: Boolean,
        accessibilityConnected: Boolean,
        canDrawOverlays: Boolean
    ): List<Route> {
        if (sdkInt < FIRST_RESTRICTED_SDK || appVisible) return listOf(Route.DIRECT)
        return buildList {
            if (accessibilityConnected) add(Route.ACCESSIBILITY)
            if (canDrawOverlays) add(Route.OVERLAY)
        }
    }

    /**
     * Что делать после попытки. Прямой запуск не проверяем (видимое окно —
     * безусловное исключение). Если проверить нечем ([Verification.UNKNOWN]:
     * служба доступности не подключена), оверлейный путь считаем сработавшим:
     * он документированное исключение, а дальше идти некуда.
     */
    fun afterAttempt(route: Route, verification: Verification): Step = when {
        route == Route.DIRECT -> Step.DONE
        verification == Verification.NOT_CONFIRMED -> Step.TRY_NEXT
        else -> Step.DONE
    }
}
