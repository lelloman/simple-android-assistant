plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lelloman.simpleaiassistant.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    api(project(":assistant-core"))

    api(libs.kotlinx.android.coroutines)
    api(libs.androidx.lifecycle.viewmodel.ktx)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    api(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.ui.tooling)
}

apply(from = rootProject.file("gradle/publish-library.gradle.kts"))
