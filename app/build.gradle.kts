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

// An APK without the key cannot reach the CRM ("CRM connection is not built into this app").
gradle.taskGraph.whenReady {
    if (embeddedMobileSyncApiKey.isBlank() && allTasks.any { it.name.startsWith("assemble") || it.name.startsWith("bundle") }) {
        throw GradleException(
            "MOBILE_SYNC_API_KEY is not set. Add it to ~/.gradle/gradle.properties or the environment before building an APK."
        )
    }
}

android {
    namespace = "com.prosync.crmcompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prosync.crmcompanion"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.8.2-workforce-sign-in"
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
