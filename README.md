# Simple Assistant

A shared Rust assistant engine with Kotlin/Compose and TypeScript/Vue integrations.
Hosts supply their providers, authentication, tools and application prompts. The
engine owns conversation state, mode navigation, tool orchestration and context
summarization.

The repository URL and existing Android artifact names remain unchanged. This is
the **0.2 API migration**, including the account-isolation and SimpleAI streaming
changes from `fbdc72f`. Licensed under [Apache 2.0](LICENSE).

## Structure

| Area | Contents |
| --- | --- |
| `crates/` | Engine, JNI bridge and WASM bridge |
| `android/` | Core AAR/Room adapter, Compose UI, Ollama and SimpleAI providers |
| `web/` | Browser session facade, Vue components and five provider adapters |
| `fixtures/` | Protocol scenarios exercised through Rust, JNI and WASM |
| `examples/web/` | Runnable Vue example using a simulated provider |

No Kotlin or JavaScript implementation of the conversation loop remains. Both
platform facades execute requests issued by the Rust state machine and return
results tagged with the originating request ID.

## Development

Required tools:

- Rust **1.96.0**, including the targets in `rust-toolchain.toml`.
- JDK 17+, Android SDK 36, and NDK **27.0.12077973**.
- `cargo-ndk` **4.1.2** and `wasm-bindgen-cli` **0.2.100**.
- Node 22+ and npm. JavaScript dependencies are pinned by `package-lock.json`.

```sh
cargo install cargo-ndk --version 4.1.2 --locked
cargo install wasm-bindgen-cli --version 0.2.100 --locked
npm ci
npm run build
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
npm run typecheck
npm test
npx playwright install chromium
npm run test:browser
./gradlew check publishToMavenLocal
```

`npm run build` builds WASM and all three web packages. The Gradle build packages
native libraries for ARM32, ARM64, x86 and x86_64; `-PassistantAbis=arm64-v8a,x86_64`
can restrict local development builds. Release verification uses all four ABIs.
The native linker uses 16 KB page alignment.

Run the browser example with:

```sh
npx vite --config examples/web/vite.config.js
# Open /examples/web/
```

## Browser integration

Packages:

- `@lelloman/simple-assistant`: framework-independent session and WASM asset.
- `@lelloman/simple-assistant-vue`: `AssistantPanel`, `AssistantButton`, `useAssistant`.
- `@lelloman/simple-assistant-providers`: Anthropic, OpenAI, OpenRouter, Google,
  and Ollama adapters extracted from Pezzottify.

```ts
import { AssistantSession } from '@lelloman/simple-assistant';
import { createProvider } from '@lelloman/simple-assistant-providers';

const session = await AssistantSession.create({
  config: {
    basePrompt: 'Help with this application.',
    rootMode: {
      id: 'general', name: 'General', prompt: 'Be concise.', toolIds: ['search'],
      children: [{
        id: 'discovery', name: 'Discovery', prompt: 'Focus on finding content.',
        inheritPrompts: ['general'], toolIds: ['search'],
      }],
    },
    tools: [{ name: 'search', description: 'Search content', inputSchema: {
      type: 'object', properties: { query: { type: 'string' } }, required: ['query'],
    } }],
  },
  provider: createProvider('ollama', { baseUrl: 'http://localhost:11434', model: 'llama3.3' }),
  executeTool: async (call, signal) => {
    signal.throwIfAborted();
    return searchApplication(call.input.query, signal);
  },
});
const unsubscribe = session.subscribe(state => renderChat(state));
session.send('Find something interesting');
// On teardown: unsubscribe(); await session.dispose();
```

For Vue, bind a `shallowRef<AssistantSession | null>` with `useAssistant`, and pass
a reactive controller to the panel/button. See the example for the complete
controller contract, settings, mode labels, and lifecycle. Import
`@lelloman/simple-assistant-vue/style.css`. CSS variables use the `--assistant-`
prefix and include defaults; Pezzottify supplies its colors and player offsets.

History is in memory unless a `HistoryStore` is supplied. Stores receive opaque,
versioned archive data and must be owned by the current account. Consumers await
`clear()`/`dispose()` before reusing a store for another owner. A new session must
receive a new `sessionId` (generated automatically by default).

## Android integration

Maven coordinates remain `com.github.lelloman.simple-android-assistant` with
artifacts `assistant-core`, `assistant-compose`, `provider-ollama` and
`provider-simpleai`. Existing published versions do not contain this new API.
Use a composite checkout while preparing a new release.

`AssistantSession.create` takes `AssistantConfig`, `LlmProvider`, a suspending tool
executor, `AssistantHistoryStore` and a host `CoroutineScope`. Its `state` is a
`StateFlow<AssistantState>`; operations include `send`, `cancel`, `clear`,
`switchMode`, `restart`, `confirmRestart`, `setLanguage` and `close`.

`ChatRepositoryImpl` adapts this session to the Compose repository API. Its storage
argument is now an `AssistantHistoryStore`; it also requires the host scope.
Use `RoomHistoryStore(database, defaultModeId)` and register
`ChatDatabase.MIGRATION_1_2` with the Room builder. The migration preserves existing
message IDs and deterministic order, importing messages into the engine archive.
Old messages with no mode metadata require a mode choice when restarted.

`ChatScreen` adds `onCancel`, `onConfirmRestart`, and `onDismissRestart` callbacks.
Expose repository `error` and `restartChoice` in `ChatUiState`. Pezzottify's
integration provides an example of the complete wiring.

The SimpleAI provider retains capability negotiation, incremental streaming,
remote cancellation, service-death handling, deadlines, and fallback to older
companion apps. Kotlin provider configuration stays outside Rust.

## Modes and history

Every mode has its own prompt and an explicit tool list. Browser `inheritPrompts`
is `"all"`, a list of ancestor IDs, or `[]` (default). Kotlin uses
`inheritAllAncestorPrompts` or `inheritPromptsFrom`. Selected ancestor **own prompts**
are composed in root-to-parent order, followed by the active mode's own prompt.
The optional base prompt always precedes them. Tools do not inherit.

Rules are prompt guidance. The engine enforces current-mode tool availability,
input validation for the supported JSON-schema subset, and a ten-round tool
budget. Hosts still enforce permissions, business validation and confirmation.
A tool already dispatched cannot be undone by cancellation or transcript restart.

Full transcript messages are never deleted by summarization. The model receives
a summary plus recent complete turns. Default thresholds are approximately 4,000
tokens for the recent window and 4,000 unsummarized older tokens; both are
configurable. Estimation includes message/tool data and is not a model tokenizer.
Summary requests are bounded chronological fragments. Summarization failure keeps
the previous context and does not discard messages.

Each user turn references a saved mode definition. Restart restores that mode,
retains the selected message and earlier history, and rebuilds model context
without the abandoned continuation. If an app update changes/removes the mode,
`restartChoice` is populated before any truncation. Confirm with a current mode
and the supplied revision, or dismiss. A stale selection cannot truncate newer
history. Mode `revision` can be bumped for host behavior changes that do not alter
prompts or tool specifications.

Account changes must cancel the old session before credentials change and clear
its transcript and summary. Android's `AccountChatRepository` preserves the
single-owner policy: same-account reopening retains history; logout, unknown
legacy ownership, or another account clears it. Browser Pezzottify preserves its
existing in-memory transcript policy and clears provider credentials on logout.

## Packaging and release

```sh
mkdir -p target/packages
npm run pack:all
./gradlew check publishToMavenLocal -PVERSION_NAME=0.2.0
```

npm archives contain built JavaScript, declarations and the WASM asset. Applications
installing these archives or published packages do not need Rust. The AAR contains
its native libraries. Source-checkout consumers need the documented toolchains.

JitPack uses `jitpack.yml`; custom Maven repositories retain the existing
`RELEASE_REPOSITORY_*` and `SIGNING_*` configuration. No npm/Maven remote publication
or GitHub rename is performed by the build. See [architecture](docs/architecture.md)
and [verification](docs/verification.md) for protocol and migration details.
