plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.openrouteragent"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.icalchemy.openrouteragent"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.5.0"
        val moderationReportUrl = providers.gradleProperty("moderationReportUrl").orNull.orEmpty()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "MODERATION_REPORT_URL", "\"$moderationReportUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress.add("tflite") }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    signingConfigs.getByName("debug") {
        providers.gradleProperty("agentDebugKeystore").orNull?.let { storeFile = file(it) }
    }
    buildTypes { debug { applicationIdSuffix = ".studio" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Print the full assertion message and stack trace of a failing test, so CI logs carry the cause.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.bytedeco:tensorflow-lite-platform:2.21.0-1.5.14")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
