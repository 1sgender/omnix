package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Пиннинг моделей по sha256 (owner review 2026-09-26, п.7): код не должен
 * запускать ONNX с чужим дайджестом. Здесь — сам дайджест и синхронность
 * EXPECTED с файлами assets (тот же приём, что в WakeWordAssetsTest).
 */
class ModelDigestsTest {

    @Test
    fun sha256Hex_matchesKnownVector() {
        // sha256("abc") — эталонный вектор FIPS 180.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelDigests.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun sha256Hex_emptyInputIsDigestOfEmptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelDigests.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun verify_acceptsUnpinnedModels() {
        assertNull(ModelDigests.verify("wakeword/unknown_model.onnx", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun verify_rejectsCorruptedBytes() {
        val path = "wakeword/omni_v0.1.onnx"
        val result = ModelDigests.verify(path, byteArrayOf(0, 1, 2))
        assertNotNull("pin существует — проверка обязательна", result)
        assertFalse("чужие байты не должны проходить пин", result!!)
    }

    @Test
    fun expectedMatchesActualAssetFiles() {
        // Регрессия пиннинга: если модель в assets поменяли — дайджест в коде
        // обязан по ней сходиться, иначе каждый запуск будет падать
        // ModelCorrupted. (Авторитетная проверка в APK — инструментальный
        // тест; здесь — исходники на этапе CI.)
        val assetsRoot = File("src/main/assets")
        assertTrue(
            "тест должен запускаться из каталога модуля app",
            assetsRoot.isDirectory
        )
        for ((path, expected) in ModelDigests.EXPECTED) {
            val asset = File(assetsRoot, path)
            assertTrue("ассет отсутствует: $path", asset.isFile)
            assertEquals(
                "дайджест $path в ModelDigests не совпадает с файлом — обновите пин",
                expected,
                ModelDigests.sha256Hex(asset.readBytes())
            )
        }
    }
}
