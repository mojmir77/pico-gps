plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.picogps.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.picogps.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.directories.add(rootProject.file("../protocol").absolutePath)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.maplibre.gl:android-sdk-opengl:13.5.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
