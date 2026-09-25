plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val embeddedMobileSyncApiKey = providers.environmentVariable("MOBILE_SYNC_API_KEY")
    .orElse(providers.gradleProperty("MOBILE_SYNC_API_KEY"))
    .orElse("")
    .get()
val embeddedCrmBaseUrl = providers.environmentVariable("CRM_BASE_URL")
    .orElse(providers.gradleProperty("CRM_BASE_URL"))
    .orElse("https://crm.prosyncedu.com")
    .get()

android {
    namespace = "com.prosync.crmcompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prosync.crmcompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.2-durable-auto-sync"
        buildConfigField("String", "MOBILE_SYNC_API_KEY", quotedBuildConfig(embeddedMobileSyncApiKey))
        buildConfigField("String", "CRM_BASE_URL", quotedBuildConfig(embeddedCrmBaseUrl.trimEnd('/')))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
