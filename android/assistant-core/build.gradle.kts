plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lelloman.simpleaiassistant"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    api(libs.androidx.annotation)
    api(libs.kotlinx.android.coroutines)
    api(libs.kotlinx.serialization.json)
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.ksp.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

apply(from = rootProject.file("gradle/publish-library.gradle.kts"))

val buildAssistantNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash", "scripts/build-android.sh")
    environment("ASSISTANT_ABIS", providers.gradleProperty("assistantAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64,x86"))
    inputs.files(rootProject.fileTree("crates"), rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"), rootProject.file("scripts/build-android.sh"))
    inputs.property("abis", providers.gradleProperty("assistantAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64,x86"))
    outputs.dir(layout.buildDirectory.dir("generated/assistant/jniLibs"))
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/assistant/jniLibs").get().asFile)
tasks.named("preBuild").configure { dependsOn(buildAssistantNative) }
val buildAssistantHost by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("cargo", "build", "--locked", "-p", "assistant-jni")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildAssistantHost)
    systemProperty("assistant.fixtures", rootProject.file("fixtures").absolutePath)
    systemProperty("java.library.path", rootProject.file("target/debug").absolutePath)
}
