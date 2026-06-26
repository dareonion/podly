plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin-android is gone (AGP built-in Kotlin); kotlin-jvm stays for the :generator module.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
