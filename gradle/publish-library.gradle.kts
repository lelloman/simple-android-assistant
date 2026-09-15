import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins.apply("maven-publish")
plugins.apply("signing")

val projectUrl = "https://github.com/lelloman/simple-android-assistant"
val publicationDescription = when (project.name) {
    "assistant-core" -> "Core conversation, modes, persistence, and tool orchestration APIs."
    "assistant-compose" -> "Reusable Jetpack Compose UI for Simple Assistant."
    "provider-ollama" -> "Ollama provider for Simple Assistant."
    "provider-simpleai" -> "SimpleAI Android provider for Simple Assistant."
    else -> project.description ?: project.name
}

val releaseRepositoryUrl = providers.gradleProperty("RELEASE_REPOSITORY_URL")
    .orElse(providers.environmentVariable("RELEASE_REPOSITORY_URL"))
val releaseRepositoryUsername = providers.gradleProperty("RELEASE_REPOSITORY_USERNAME")
    .orElse(providers.environmentVariable("RELEASE_REPOSITORY_USERNAME"))
val releaseRepositoryPassword = providers.gradleProperty("RELEASE_REPOSITORY_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_REPOSITORY_PASSWORD"))

extensions.configure<PublishingExtension> {
    repositories {
        if (releaseRepositoryUrl.isPresent) {
            maven {
                name = "release"
                url = uri(releaseRepositoryUrl.get())
                if (releaseRepositoryUsername.isPresent || releaseRepositoryPassword.isPresent) {
                    credentials(PasswordCredentials::class) {
                        username = releaseRepositoryUsername.orNull
                        password = releaseRepositoryPassword.orNull
                    }
                }
            }
        }
    }
}

afterEvaluate {
    val publishing = extensions.getByType<PublishingExtension>()
    val publication = publishing.publications.create("release", MavenPublication::class.java) {
        from(components.getByName("release"))
        artifactId = project.name

        pom {
            name.set("Simple Assistant: ${project.name}")
            description.set(publicationDescription)
            url.set(projectUrl)

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }

            developers {
                developer {
                    id.set("lelloman")
                    name.set("Domenico Cerasuolo")
                    email.set("cerasuolodomenico@gmail.com")
                }
            }

            scm {
                connection.set("scm:git:https://github.com/lelloman/simple-android-assistant.git")
                developerConnection.set("scm:git:ssh://git@github.com/lelloman/simple-android-assistant.git")
                url.set(projectUrl)
            }
        }
    }

    val signingKey = providers.gradleProperty("SIGNING_KEY")
        .orElse(providers.environmentVariable("SIGNING_KEY"))
        .orNull
    val signingPassword = providers.gradleProperty("SIGNING_PASSWORD")
        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
        .orNull

    if (!signingKey.isNullOrBlank()) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publication)
        }
    }
}

tasks.withType<Jar>().configureEach {
    if (name == "sourceReleaseJar" || name == "javaDocReleaseJar") {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
    }
}
