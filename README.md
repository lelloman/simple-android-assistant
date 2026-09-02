# Simple Android Assistant

Reusable Android assistant engine, Compose UI, and LLM provider adapters.
Host applications inject their own internal tools, prompt, authentication, and
navigation while the library owns conversation orchestration and presentation.

[![JitPack](https://jitpack.io/v/lelloman/simple-android-assistant.svg)](https://jitpack.io/#lelloman/simple-android-assistant)

Licensed under the [Apache License 2.0](LICENSE).

## Modules

- `assistant-core`: messages, tool registry, modes, Room history, and the LLM loop
- `assistant-compose`: reusable Compose chat UI and view model
- `provider-simpleai`: Android AIDL client for the SimpleAI companion app
- `provider-ollama`: direct Ollama provider

## Installation

Published releases are available from JitPack. Add the repository after
Google and Maven Central:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

This is a multi-module project. Depend only on the modules needed by the host
application, replacing `TAG` with a GitHub release tag or commit hash:

```kotlin
dependencies {
    implementation("com.github.lelloman.simple-android-assistant:assistant-core:TAG")
    implementation("com.github.lelloman.simple-android-assistant:assistant-compose:TAG")
    implementation("com.github.lelloman.simple-android-assistant:provider-ollama:TAG")
    implementation("com.github.lelloman.simple-android-assistant:provider-simpleai:TAG")
}
```

## Tool security

Tool calls and arguments originate from an LLM and are untrusted. Every tool
must validate its input and enforce the Android permissions, application-level
authorization, and user confirmation appropriate for the operation. Register
only tools that are safe to expose to the active assistant mode.

Chat messages and tool results are persisted by the supplied Room entities in
the host application's private storage. Hosts handling sensitive conversations
should define an appropriate retention, backup, and database-encryption policy.

The Ollama provider supports HTTP for local development. Prefer HTTPS for
remote servers; any cleartext exception is controlled by the host application's
Android network security configuration.

## Development

```shell
./gradlew clean check assembleRelease
```

## Publishing

JitPack runs the commands declared in `jitpack.yml` and publishes every module
to its local Maven repository. Before creating a Git tag or GitHub release,
verify the same publication path locally:

```shell
./gradlew clean check publishToMavenLocal
```

Local or custom Maven publications can override the version with
`-PVERSION_NAME=1.0.0`. A custom Maven repository can be selected with
`RELEASE_REPOSITORY_URL`; optional username/password and in-memory PGP signing
use the corresponding `RELEASE_REPOSITORY_*` and `SIGNING_*` environment
variables.
