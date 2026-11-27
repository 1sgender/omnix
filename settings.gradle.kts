// Официальное зеркало Maven Central у Google — первым: repo.maven.apache.org отвечает раннерам
// GitHub Actions 429 Too Many Requests (общие IP), из-за этого CI падал на скачивании Hilt, Guava
// и других библиотек. Для библиотек артефакты те же (контрольные суммы проверяет verification-metadata.xml),
// mavenCentral() и портал плагинов остаются запасными.
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    // Маркеры плагинов на портале Gradle и в Maven Central — разные файлы (их суммы в
    // verification-metadata.xml — с портала), поэтому портал первым, зеркало — последним запасным.
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(mavenCentralMirror) { name = "MavenCentralGoogleMirror" }
        mavenCentral()
    }
}

rootProject.name = "OMNIX"
include(":app")

// Play Asset Delivery: 521 МБ LLM в install-time паке (в базу Play не влезть —
// лимит 200 МБ). Sideload-APK пак не везут: там работает сетевая докачка.
include(":assetpacks:localmodel")

// Этап 3 — OMNIX API (server-side AI orchestration).
// Android-сборка (:app) от этого модуля не зависит и собирается независимо.
include(":server")
