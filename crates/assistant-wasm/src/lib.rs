use assistant_engine::Engine;
use wasm_bindgen::prelude::*;

#[wasm_bindgen]
pub struct AssistantEngine(Engine);
#[wasm_bindgen]
impl AssistantEngine {
    #[wasm_bindgen(constructor)]
    pub fn new(
        config: &str,
        session_id: &str,
        archive: Option<String>,
    ) -> Result<AssistantEngine, JsValue> {
        Engine::from_json(config, session_id, archive.as_deref())
            .map(Self)
            .map_err(|e| JsValue::from_str(&e))
    }
    pub fn dispatch(&mut self, command: &str) -> Result<String, JsValue> {
        self.0
            .dispatch_json(command)
            .map_err(|e| JsValue::from_str(&e))
    }
}
