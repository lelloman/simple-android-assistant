# Verification

## Library checks

```sh
cargo test --workspace --locked
cargo clippy --workspace --all-targets --locked -- -D warnings
npm ci
npm run build
npm run typecheck
npm test
npm run test:browser
npm run pack:all
./gradlew check publishToMavenLocal -PVERSION_NAME=0.2.0 --offline
```

The Rust suite covers prompt inheritance, explicit tool availability, malformed
streams, cancellation, the tool-round limit, summarization and restart. The shared
JSON fixture runs through Rust, real WASM and real JNI. Host tests cover persistence
ordering, late callbacks, failed writes and Room migration from the old message
schema. Browser tests exercise streamed tool turns, mode changes, clear and the
changed-mode restart dialog using the actual WASM engine.

## Pezzottify migration

Both consumers use the sibling source checkout until these packages are released.
Build this repository's web packages before running Pezzottify's web commands.

The migrated Pezzottify web unit suite and production build pass. Android's app
compilation and UI/domain unit suites pass against the included assistant build.
The host owns music prompts, tool implementations, confirmation, account lifecycle
and credentials; the library owns conversation orchestration.

## Scope

Verification uses deterministic provider fixtures. No live provider requests,
physical Android device runs, remote JitPack builds or external package publication
were performed. Android artifacts are built for arm64-v8a, armeabi-v7a, x86_64 and
x86. Local Maven publication and npm archives are intended for release review.

## Artifact checks

The three 0.2.0 npm archives were installed in an isolated temporary project and
built with Vite. The production output contains the WASM asset, Vue components
and stylesheet without workspace links. npm reports zero advisories for this
repository's dependencies at verification time.

The release AAR contains all four expected `libsimple_assistant.so` files. Each
ELF LOAD segment has an alignment of 16,384 bytes. This is an artifact check;
device installation and runtime behavior still require device testing.

Final results: 11 Rust engine tests, shared JNI/WASM conformance, Android Gradle
`check`, three browser tests, web library tests/type checks, and Pezzottify's 22
web unit tests passed. Pezzottify reconnect coverage verifies that the tool catalog
refreshes while retaining earlier transcript messages.
