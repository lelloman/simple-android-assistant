plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.ksp) apply false
}

allprojects {
    group = "com.lelloman.simpleandroidassistant"
    version = "0.1.0-SNAPSHOT"
}
