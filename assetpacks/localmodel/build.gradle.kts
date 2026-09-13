plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("localmodel")
    dynamicDelivery {
        // install-time, а не fast-follow: fast-follow паки ограничены 512 МБ,
        // а модель — 521 МБ. install-time доступен сразу при первом запуске
        // через обычный AssetManager, без Play-библиотеки в приложении.
        deliveryType.set("install-time")
    }
}

// Источник правды для URL/размера — LocalModelSpec.QWEN2_5_0_5B_INSTRUCT_Q8
// (app/.../agent/localai/LocalAiModels.kt). Gradle не читает Kotlin-объекты,
// поэтому значения продублированы здесь руками — при смене модели обновить
// ОБА места, иначе сборка упадёт на проверке размера (это задумано).
val localModelFileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
val localModelUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
    "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
val localModelSizeBytes = 546660344L

val packAssetsDir = layout.projectDirectory.dir("src/main/assets")

/**
 * Гарантия порядка: паковаться пак должен строго ПОСЛЕ скачивания модели.
 * Имена packaging-тасков AGP меняются между версиями — ловим маской
 * (matching ленивый, лишнего не конфигурирует). Debug-APK в граф пака
 * не входят, поэтому assemble/тесты модель не тянут — только bundle*.
 */
tasks.matching {
    it.name.startsWith("package", ignoreCase = true) ||
        it.name.startsWith("assemble", ignoreCase = true) ||
        (it.name.contains("bundle", ignoreCase = true) && it.name != "downloadLocalModel")
}.configureEach {
    dependsOn("downloadLocalModel")
}

/**
 * Качает 521 МБ .task в пак. Up-to-date проверка — размер файла байт в байт,
 * повторные сборки ничего не качают.
 */
tasks.register("downloadLocalModel") {
    group = "localmodel"
    description = "Downloads the 521 MB LLM .task into the install-time asset pack (Play bundles only)."
    onlyIf("model missing or wrong size") {
        val f = packAssetsDir.file(localModelFileName).asFile
        !f.isFile || f.length() != localModelSizeBytes
    }
    doLast {
        val dest = packAssetsDir.file(localModelFileName).asFile
        dest.parentFile.mkdirs()
        val tmp = File(dest.parentFile, "$localModelFileName.part")
        logger.lifecycle("Downloading local model (521 MB) into asset pack...")
        java.net.URI(localModelUrl).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val got = tmp.length()
        if (got != localModelSizeBytes) {
            tmp.delete()
            throw GradleException(
                "model size mismatch: got $got, want $localModelSizeBytes " +
                    "(upstream changed the file? update LocalModelSpec AND this script)"
            )
        }
        if (!tmp.renameTo(dest)) throw GradleException("cannot move model into $dest")
        logger.lifecycle("Local model ready: $dest")
    }
}
