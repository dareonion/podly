import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val podlyReleaseStoreFile = localProperty("PODLY_RELEASE_STORE_FILE")
val podlyReleaseKeyAlias = localProperty("PODLY_RELEASE_KEY_ALIAS")
val podlyReleaseStorePassword = localProperty("PODLY_RELEASE_STORE_PASSWORD")
val podlyReleaseKeyPassword = localProperty("PODLY_RELEASE_KEY_PASSWORD")
val hasPodlyReleaseSigning = listOf(
    podlyReleaseStoreFile,
    podlyReleaseKeyAlias,
    podlyReleaseStorePassword,
    podlyReleaseKeyPassword,
).all { it != null }

plugins {
    alias(libs.plugins.android.application)
    // AGP 9+ has built-in Kotlin, so kotlin-android is no longer applied here.
    // The Compose/serialization plugins inherit the Kotlin version from AGP.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.podly"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.podly"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (hasPodlyReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(podlyReleaseStoreFile!!)
                keyAlias = podlyReleaseKeyAlias
                storePassword = podlyReleaseStorePassword
                keyPassword = podlyReleaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasPodlyReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // With AGP built-in Kotlin, Kotlin's jvmTarget is synced from compileOptions above.
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

// Room writes a JSON snapshot per schema version (checked in) so migrations
// stay reviewable and testable.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
