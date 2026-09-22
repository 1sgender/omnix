plugins {
    // Версии плагинов — ТОЛЬКО в gradle/libs.versions.toml (один источник
    // правды); здесь и в модулях — alias() без версий.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.asset.pack) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    // com.android.library удалён при миграции: ни один модуль его не применял.
}

allprojects {
    dependencyLocking {
        // Every resolved direct/transitive dependency must be reviewable in VCS.
        lockAllConfigurations()
    }
}

tasks.register("phase3Coverage") {
    group = "verification"
    description = "Runs Android JVM and server tests and generates JaCoCo reports."
    dependsOn(
        ":app:jacocoDevDebugCoverageVerification",
        ":server:jacocoTestReport",
        ":server:jacocoTestCoverageVerification"
    )
}

tasks.register("phase3StaticAnalysis") {
    group = "verification"
    description = "Runs Detekt for Android and server Kotlin sources."
    dependsOn(":app:detekt", ":server:detekt")
}

tasks.register("phase3Quality") {
    group = "verification"
    description = "Runs static analysis, coverage reports and Android lint."
    dependsOn("phase3StaticAnalysis", "phase3Coverage", ":app:lintDevDebug")
}
