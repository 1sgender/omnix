package com.omnix.assistant.core.permission

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Виден ли экран OMNIX прямо сейчас (хотя бы одна наша Activity в состоянии
 * started). Android 10+ не даёт запускать чужие приложения из фона, пока у
 * нашего нет видимого окна: это нужно знать до запуска, а не гадать по тишине
 * после.
 */
object AppVisibility {
    private val started = AtomicInteger(0)

    val isForeground: Boolean get() = started.get() > 0

    internal fun onActivityStarted() {
        started.incrementAndGet()
    }

    internal fun onActivityStopped() {
        started.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    internal fun resetForTest() {
        started.set(0)
    }

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = this@AppVisibility.onActivityStarted()
            override fun onActivityStopped(activity: Activity) = this@AppVisibility.onActivityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
