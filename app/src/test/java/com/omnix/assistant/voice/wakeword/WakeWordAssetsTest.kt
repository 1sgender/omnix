package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ассеты wake-word, на которые ссылается дефолтный [WakeWordConfig], реально
 * лежат в src/main/assets — регрессия аудита 1.4: опечатка в modelAssetPath
 * проходит CI (юнит-тесты движка работают на фейках) и падает только у
 * пользователя экраном «модель не найдена».
 *
 * Gradle запускает JVM-тесты с workingDir = каталог модуля app/, поэтому
 * путь src/main/assets корректен и в CI, и в Android Studio.
 * Авторитетная проверка упаковки в сам APK — инструментальный тест
 * WakeWordAssetsInstrumentedTest (эмулятор).
 */
class WakeWordAssetsTest {

    @Test
    fun `ассеты из дефолтного конфига существуют в каталоге assets и непусты`() {
        val config = WakeWordConfig()
        val assetsRoot = File("src/main/assets")
        assertTrue(
            "каталог ассетов не найден: ${assetsRoot.absolutePath} " +
                "(тест должен запускаться из каталога модуля app)",
            assetsRoot.isDirectory
        )

        val referenced = listOf(
            "model" to config.modelAssetPath,
            "mel" to config.melAssetPath,
            "embedding" to config.embeddingAssetPath
        )
        for ((role, path) in referenced) {
            val asset = File(assetsRoot, path)
            assertTrue("wake-word $role-ассет отсутствует: $path", asset.isFile)
            assertTrue("wake-word $role-ассет пустой: $path", asset.length() > 0L)
        }
    }
}
