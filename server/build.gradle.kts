plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    application
    jacoco
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.omnix.server.MainKt")
    // Runtime defaults baked into the start script (override with JAVA_OPTS).
    // maxReqTime / maxRspTime make the JDK HttpServer close a connection that takes
    // too long to send its request or to finish its response (default: no limit).
    // The server request deadline is 28 s, so 60 s for the response leaves room.
    // MaxRAMPercentage sizes the heap from the container limit, and an out-of-memory
    // error ends the process so the restart policy replaces it instead of leaving a
    // half-working JVM behind.
    applicationDefaultJvmArgs = listOf(
        "-Dsun.net.httpserver.maxReqTime=30",
        "-Dsun.net.httpserver.maxRspTime=60",
        "-XX:MaxRAMPercentage=70.0",
        "-XX:+ExitOnOutOfMemoryError"
    )
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.core)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    basePath = rootProject.projectDir.absolutePath
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    exclude("**/build/**", "**/generated/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<Test>().configureEach {
    useJUnit()
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>(
    "jacocoTestCoverageVerification"
) {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.35".toBigDecimal()
            }
        }
    }
}

tasks.named("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<Test>("test") {
    // Live credentials are never part of the ordinary/PR test environment.
    exclude("**/LiveProviderStagingSmokeTest.class")
}

tasks.register<Test>("liveProviderSmokeTest") {
    description = "Runs three authorized, low-volume live provider staging requests"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/LiveProviderStagingSmokeTest.class")
    outputs.upToDateWhen { false }
}
