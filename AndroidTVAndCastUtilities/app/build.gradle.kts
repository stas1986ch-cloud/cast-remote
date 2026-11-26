/**
 * @file build.gradle.kts
 * @summary Gradle module configuration for Google Screen Control and Cast (v3.5).
 * @description Configures SDK 35, authorized Google Home OAuth applicationId, Kotlin 2.4.0 stdlib alignment, and Play Services Home 17.1.0 dependencies.
 * @testing Validated live on emulator-5554 against Master Bedroom TV and via gradlew assembleRelease + lintVitalRelease.
 */
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.project.autoswitch.ui"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stas.castremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "3.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/release-key.jks")
            storePassword = "ProjectAutoSwitch2026!"
            keyAlias = "autoswitch-key"
            keyPassword = "ProjectAutoSwitch2026!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with Android debug keystore (SHA1 BC:91:2C:95:...) registered in Google Home Developer Console
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.4.0")
        }
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) {
            useVersion("1.11.0")
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.ads)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-home:17.1.0")
    implementation("com.google.android.gms:play-services-home-types:17.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
