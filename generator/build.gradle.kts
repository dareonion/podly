import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    // Generates the static recommendation JSON files published to GitHub Pages.
    mainClass.set("com.podly.generator.MainKt")
}

// Target 17 bytecode (matching the app) while compiling on whatever JDK runs Gradle,
// so this builds both in CI (temurin 17) and locally without a strict 17 toolchain.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.anthropic.java)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
