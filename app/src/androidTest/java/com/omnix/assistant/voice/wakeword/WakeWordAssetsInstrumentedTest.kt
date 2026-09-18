package com.omnix.assistant.voice.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ассеты wake-word из дефолтного [WakeWordConfig] реально упакованы в APK.
 *
 * В отличие от JVM-теста (WakeWordAssetsTest, файловая система модуля),
 * здесь открываются именно ассеты установленного APK: ловит случаи
 * «файл есть в репо, но не попал в пакет» (исключён из source set,
 * сломан merge в flavor-каталог и т.п.).
 */
@RunWith(AndroidJUnit4::class)
class WakeWordAssetsInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun defaultConfigAssetsArePackagedInApk() {
        val config = WakeWordConfig()
        val referenced = listOf(
            "model" to config.modelAssetPath,
            "mel" to config.melAssetPath,
            "embedding" to config.embeddingAssetPath
        )
        for ((role, path) in referenced) {
            // assets.open кидает IOException, если ассета нет — тест краснеет.
            context.assets.open(path).use { stream ->
                val firstByte = stream.read()
                assertTrue("wake-word $role-ассет пустой в APK: $path", firstByte != -1)
            }
        }
    }
}
