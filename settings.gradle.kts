pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
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
