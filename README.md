# Simple Android Assistant

Reusable Android assistant engine, Compose UI, and LLM provider adapters.
Host applications inject their own internal tools, prompt, authentication, and
navigation while the library owns conversation orchestration and presentation.

## Modules

- `assistant-core`: messages, tool registry, modes, Room history, and the LLM loop
- `assistant-compose`: reusable Compose chat UI and view model
- `provider-simpleai`: Android AIDL client for the SimpleAI companion app
- `provider-ollama`: direct Ollama provider

## Development

```shell
./gradlew check assembleDebug
```

The project currently publishes no remote artifact. Consumer applications can
use a Gradle composite build and depend on modules using coordinates such as
`com.lelloman.simpleandroidassistant:assistant-core:0.1.0-SNAPSHOT`.
