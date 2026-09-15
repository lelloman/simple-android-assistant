use assistant_engine::Engine;
use serde_json::{json, Value};
fn config() -> Value {
    json!({"basePrompt":"BASE", "rootMode":{"id":"root","name":"Root","prompt":"ROOT","toolIds":["read"],"children":[{"id":"child","name":"Child","prompt":"CHILD","inheritPrompts":["root"],"toolIds":[],"children":[{"id":"leaf","name":"Leaf","prompt":"LEAF","inheritPrompts":["root"],"toolIds":["read"]}]}]},"tools":[{"name":"read","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"],"additionalProperties":false}}]})
}
fn engine() -> Engine {
    let mut e = Engine::from_json(&config().to_string(), "test", None).unwrap();
    send(&mut e, json!({"type":"set_language","language":"en"}));
    e
}
fn send(e: &mut Engine, c: Value) -> Value {
    serde_json::from_str(&e.dispatch_json(&c.to_string()).unwrap()).unwrap()
}
fn event(e: &mut Engine, id: &str, event: Value) -> Value {
    send(
        e,
        json!({"type":"provider_event","requestId":id,"event":event}),
    )
}
fn request(o: &Value) -> String {
    o["effects"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["type"] == "provider")
        .unwrap()["requestId"]
        .as_str()
        .unwrap()
        .into()
}
fn text_turn(e: &mut Engine, text: &str) -> Value {
    let o = send(e, json!({"type":"send","text":text,"timestamp":1}));
    let id = request(&o);
    event(e, &id, json!({"type":"text","content":"answer"}));
    event(e, &id, json!({"type":"done"}))
}
#[test]
fn selective_inheritance_and_explicit_tools() {
    let mut e = engine();
    send(&mut e, json!({"type":"switch_mode","modeId":"leaf"}));
    let o = send(&mut e, json!({"type":"send","text":"hi"}));
    let p = o["effects"][0]["systemPrompt"].as_str().unwrap();
    assert!(p.starts_with("BASE\n\nROOT\n\nLEAF"));
    assert!(!p.contains("\n\nCHILD"));
    send(&mut e, json!({"type":"switch_mode","modeId":"child"}));
    let o = send(&mut e, json!({"type":"send","text":"hi"}));
    assert_eq!(o["effects"][0]["tools"].as_array().unwrap().len(), 1);
}
#[test]
fn validates_configuration() {
    let mut c = config();
    c["rootMode"]["children"][0]["inheritPrompts"] = json!(["leaf"]);
    assert!(Engine::from_json(&c.to_string(), "t", None).is_err());
    let mut c = config();
    c["rootMode"]["children"][0]["id"] = json!("root");
    assert!(Engine::from_json(&c.to_string(), "t", None).is_err());
}
#[test]
fn clear_fences_late_streams_and_tools() {
    let mut e = engine();
    let o = send(&mut e, json!({"type":"send","text":"old"}));
    let id = request(&o);
    send(&mut e, json!({"type":"clear"}));
    event(
        &mut e,
        &id,
        json!({"type":"tool_use","id":"t","name":"read","input":{"q":"old"}}),
    );
    let o = event(&mut e, &id, json!({"type":"done"}));
    assert!(o["effects"].as_array().unwrap().is_empty());
    assert!(o["state"]["messages"].as_array().unwrap().is_empty());
}
#[test]
fn incomplete_stream_does_not_execute_tools() {
    let mut e = engine();
    let o = send(&mut e, json!({"type":"send","text":"hi"}));
    let id = request(&o);
    event(
        &mut e,
        &id,
        json!({"type":"tool_use","id":"t","name":"read","input":{"q":"x"}}),
    );
    let o = event(
        &mut e,
        &id,
        json!({"type":"error","message":"disconnected"}),
    );
    assert!(!o["effects"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["type"] == "tool"));
    assert_eq!(o["state"]["messages"].as_array().unwrap().len(), 1);
}
#[test]
fn tool_validation_budget_and_cancellation_pairs() {
    let mut c = config();
    c["maxToolRounds"] = json!(1);
    let mut e = Engine::from_json(&c.to_string(), "test", None).unwrap();
    send(&mut e, json!({"type":"set_language","language":"en"}));
    let o = send(&mut e, json!({"type":"send","text":"hi"}));
    let id = request(&o);
    event(
        &mut e,
        &id,
        json!({"type":"tool_use","id":"bad","name":"read","input":{}}),
    );
    event(
        &mut e,
        &id,
        json!({"type":"tool_use","id":"ok","name":"read","input":{"q":"x"}}),
    );
    let o = event(&mut e, &id, json!({"type":"done"}));
    assert!(o["state"]["messages"][2]["content"]
        .as_str()
        .unwrap()
        .contains("required"));
    let tid = o["effects"][0]["requestId"].as_str().unwrap();
    let o = send(
        &mut e,
        json!({"type":"tool_result","requestId":tid,"result":"ok"}),
    );
    let id = request(&o);
    event(
        &mut e,
        &id,
        json!({"type":"tool_use","id":"budget","name":"read","input":{"q":"x"}}),
    );
    let o = event(&mut e, &id, json!({"type":"done"}));
    assert_eq!(o["state"]["activity"], "idle");
    assert!(o["state"]["messages"].to_string().contains("not executed"));
    assert!(o["effects"].as_array().unwrap().is_empty());
}
#[test]
fn restart_restores_mode_and_uses_order_not_timestamps() {
    let mut e = engine();
    let first = text_turn(&mut e, "first");
    let id = first["state"]["messages"][0]["id"].as_str().unwrap();
    send(&mut e, json!({"type":"switch_mode","modeId":"leaf"}));
    text_turn(&mut e, "future secret");
    let o = send(&mut e, json!({"type":"restart","messageId":id}));
    assert_eq!(o["state"]["modeId"], "root");
    assert_eq!(o["state"]["messages"].as_array().unwrap().len(), 1);
    assert!(!o["effects"].to_string().contains("future secret"));
}
#[test]
fn changed_modes_require_selection_without_truncation() {
    let mut e = engine();
    text_turn(&mut e, "first");
    let o = text_turn(&mut e, "second");
    let archive: assistant_engine::Archive = serde_json::from_value(o["state"].clone()).unwrap();
    let id = archive.messages[0].id.clone();
    let mut c = config();
    c["rootMode"]["prompt"] = json!("UPDATED");
    let mut e = Engine::new(
        serde_json::from_value(c).unwrap(),
        "new".into(),
        Some(archive),
    )
    .unwrap();
    let o = send(&mut e, json!({"type":"restart","messageId":id}));
    assert_eq!(o["state"]["messages"].as_array().unwrap().len(), 4);
    assert!(o["effects"].as_array().unwrap().is_empty());
    let revision = o["state"]["restartChoice"]["revision"].clone();
    let o = send(
        &mut e,
        json!({"type":"confirm_restart","modeId":"leaf","revision":revision}),
    );
    assert_eq!(o["state"]["messages"].as_array().unwrap().len(), 1);
    assert_eq!(o["state"]["modeId"], "leaf");
}
#[test]
fn compaction_keeps_full_history_and_restart_rebuilds_only_prefix() {
    let mut c = config();
    c["keepRecentTokens"] = json!(30);
    c["summaryThresholdTokens"] = json!(50);
    let mut e = Engine::from_json(&c.to_string(), "test", None).unwrap();
    send(&mut e, json!({"type":"set_language","language":"en"}));
    text_turn(&mut e, &"old ".repeat(80));
    let o = send(&mut e, json!({"type":"send","text":"future secret"}));
    let old_id = o["state"]["messages"][0]["id"]
        .as_str()
        .unwrap()
        .to_string();
    let mut out = o;
    while out["effects"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["purpose"] == "summary")
    {
        let id = request(&out);
        event(&mut e, &id, json!({"type":"text","content":"summary"}));
        out = event(&mut e, &id, json!({"type":"done"}));
    }
    assert_eq!(out["state"]["messages"].as_array().unwrap().len(), 3);
    assert!(!out["state"]["summary"].is_null());
    let out = send(&mut e, json!({"type":"restart","messageId":old_id}));
    assert!(out["state"]["summary"].is_null());
    assert!(!out["effects"].to_string().contains("future secret"));
}
#[test]
fn summary_clear_is_fenced() {
    let mut c = config();
    c["keepRecentTokens"] = json!(20);
    c["summaryThresholdTokens"] = json!(20);
    let mut e = Engine::from_json(&c.to_string(), "t", None).unwrap();
    send(&mut e, json!({"type":"set_language","language":"en"}));
    text_turn(&mut e, "old");
    let o = send(&mut e, json!({"type":"send","text":"next"}));
    let id = request(&o);
    send(&mut e, json!({"type":"clear"}));
    event(&mut e, &id, json!({"type":"text","content":"old summary"}));
    let o = event(&mut e, &id, json!({"type":"done"}));
    assert!(o["state"]["summary"].is_null());
    assert_eq!(o["state"]["activity"], "idle");
}
#[test]
fn cancel_pairs_started_and_queued_calls() {
    let mut e = engine();
    let o = send(&mut e, json!({"type":"send","text":"hi"}));
    let id = request(&o);
    for id2 in ["a", "b"] {
        event(
            &mut e,
            &id,
            json!({"type":"tool_use","id":id2,"name":"read","input":{"q":"x"}}),
        );
    }
    event(&mut e, &id, json!({"type":"done"}));
    let o = send(&mut e, json!({"type":"cancel"}));
    let messages = o["state"]["messages"].as_array().unwrap();
    assert_eq!(messages.iter().filter(|m| m["role"] == "tool").count(), 2);
    assert!(messages[2]["content"].as_str().unwrap().contains("unknown"));
    assert!(messages[3]["content"]
        .as_str()
        .unwrap()
        .contains("not executed"));
}

#[test]
fn shared_protocol_fixture() {
    let fixture: Value =
        serde_json::from_str(include_str!("../../../fixtures/conformance.json")).unwrap();
    let mut engine = Engine::from_json(
        &fixture["config"].to_string(),
        fixture["sessionId"].as_str().unwrap(),
        None,
    )
    .unwrap();
    fn subset(actual: &Value, expected: &Value) {
        match expected {
            Value::Object(map) => {
                for (k, v) in map {
                    subset(&actual[k], v);
                }
            }
            Value::Array(items) => {
                assert_eq!(actual.as_array().unwrap().len(), items.len());
                for (i, v) in items.iter().enumerate() {
                    subset(&actual[i], v);
                }
            }
            _ => assert_eq!(actual, expected),
        }
    }
    for step in fixture["steps"].as_array().unwrap() {
        subset(
            &send(&mut engine, step["command"].clone()),
            &step["expected"],
        );
    }
}
