package com.omnix.assistant.voice.service

import android.Manifest
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omnix.assistant.presentation.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class VoiceServiceLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun missingMicrophonePermissionDoesNotRequestServiceStart() {
        val denied = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                PackageManager.PERMISSION_DENIED

            override fun startForegroundService(service: Intent): ComponentName? =
                throw AssertionError("startForegroundService must not be called")

            override fun startService(service: Intent): ComponentName? =
                throw AssertionError("startService must not be called")
        }
        assertFalse(OmnixVoiceService.start(denied))
    }

    @Test
    fun foregroundStartAcquiresWakeLockAndStopReleasesResources() {
        // Grant the microphone through the shell: without it this test used to
        // be skipped in CI, so the real pipeline start was never exercised.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        assumeTrue(
            "RECORD_AUDIO could not be granted",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        val crashDir = java.io.File(context.filesDir, "crash")
        val crashesBefore = crashDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("foreground service request was rejected", OmnixVoiceService.start(context))
            assertTrue(
                "voice service did not become active",
                eventually { shell("dumpsys activity services ${context.packageName}")
                    .contains(OmnixVoiceService::class.java.name) }
            )
            // Field report 2026-10-21: the app died right after turning OMNIX
            // on. Let the wake-word engine open its ONNX sessions and record;
            // a crash there kills this process and fails the run with its trace.
            SystemClock.sleep(PIPELINE_SURVIVAL_MS)
            // Errors the guards caught are written as crash files: show the
            // first one as the failure message, it is the real cause.
            val newCrash = crashDir.listFiles()
                ?.filter { it.name !in crashesBefore }
                ?.maxByOrNull { it.name }
            assertTrue(
                "voice pipeline reported an error: " + newCrash?.readText()?.take(3_000),
                newCrash == null
            )
            assertTrue(
                "voice service died while the pipeline was starting",
                shell("dumpsys activity services ${context.packageName}")
                    .contains(OmnixVoiceService::class.java.name)
            )
            val held = eventually { activeWakeLocks().contains(WAKE_LOCK_TAG) }
            assertTrue("partial wake lock not observed while service is active: " + activeWakeLocks(), held)

            OmnixVoiceService.stop(context)
            assertTrue(
                "voice service remained active after stop",
                eventually { !shell("dumpsys activity services ${context.packageName}")
                    .contains(OmnixVoiceService::class.java.name) }
            )
            // onDestroy can still be finishing when the service leaves the list.
            val released = eventually { !activeWakeLocks().contains(WAKE_LOCK_TAG) }
            assertTrue("wake lock leaked after service destruction: " + activeWakeLocks(), released)
        }
    }

    private companion object {
        const val PIPELINE_SURVIVAL_MS = 8_000L
        const val WAKE_LOCK_TAG = "OMNIX:BackgroundVoiceWakeLock"
    }

    /**
     * Wake locks held right now. The rest of `dumpsys power` includes the
     * "Wake Lock Log" history, which keeps the tag after the lock is released,
     * so searching the whole dump never sees a release.
     */
    private fun activeWakeLocks(): String =
        shell("dumpsys power").lineSequence()
            .dropWhile { !it.trimStart().startsWith("Wake Locks: size=") }
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .joinToString("\n")

    private fun eventually(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return true
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        return condition()
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }
}
