#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cargo build --locked --release -p assistant-wasm --target wasm32-unknown-unknown
bindgen="${WASM_BINDGEN:-wasm-bindgen}"
if [[ -x target/tools/bin/wasm-bindgen && "$bindgen" == wasm-bindgen ]]; then bindgen=target/tools/bin/wasm-bindgen; fi
"$bindgen" --target web --out-dir web/core/wasm target/wasm32-unknown-unknown/release/assistant_wasm.wasm
