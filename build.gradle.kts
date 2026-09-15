plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.ksp) apply false
}

val isJitPackBuild = providers.environmentVariable("JITPACK")
    .map(String::toBoolean)
    .getOrElse(false)
val jitPackArtifact = providers.environmentVariable("ARTIFACT").orNull
val jitPackBaseGroup = providers.environmentVariable("GROUP").orNull

val publicationGroup = if (isJitPackBuild && jitPackBaseGroup != null && jitPackArtifact != null) {
    if (jitPackBaseGroup.endsWith(".$jitPackArtifact")) {
        jitPackBaseGroup
    } else {
        "$jitPackBaseGroup.$jitPackArtifact"
    }
} else {
    "com.lelloman.simpleandroidassistant"
}

val publicationVersion = if (isJitPackBuild) {
    providers.environmentVariable("VERSION").getOrElse("0.2.0-SNAPSHOT")
} else {
    providers.gradleProperty("VERSION_NAME").getOrElse("0.2.0-SNAPSHOT")
}

allprojects {
    group = publicationGroup
    version = publicationVersion
}
