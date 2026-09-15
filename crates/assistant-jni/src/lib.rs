use assistant_engine::Engine;
use jni::{
    objects::{JClass, JString},
    sys::{jlong, jstring},
    JNIEnv,
};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
};
static ENGINES: OnceLock<Mutex<HashMap<i64, Engine>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn engines() -> &'static Mutex<HashMap<i64, Engine>> {
    ENGINES.get_or_init(Default::default)
}
fn read(env: &mut JNIEnv, s: JString) -> Result<String, String> {
    env.get_string(&s)
        .map(Into::into)
        .map_err(|e| e.to_string())
}
#[no_mangle]
pub extern "system" fn Java_com_lelloman_simpleaiassistant_engine_NativeEngine_create(
    mut env: JNIEnv,
    _: JClass,
    config: JString,
    session: JString,
    archive: JString,
) -> jlong {
    let result = (|| {
        let config = read(&mut env, config)?;
        let session = read(&mut env, session)?;
        let archive = read(&mut env, archive)?;
        let engine = Engine::from_json(
            &config,
            &session,
            if archive.is_empty() {
                None
            } else {
                Some(&archive)
            },
        )?;
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        engines()
            .lock()
            .map_err(|e| e.to_string())?
            .insert(id, engine);
        Ok::<_, String>(id)
    })();
    match result {
        Ok(id) => id,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", e);
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_lelloman_simpleaiassistant_engine_NativeEngine_dispatch(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    command: JString,
) -> jstring {
    let result = (|| {
        let command = read(&mut env, command)?;
        let mut engines = engines().lock().map_err(|e| e.to_string())?;
        let engine = engines
            .get_mut(&handle)
            .ok_or("Assistant session is closed")?;
        let output = engine.dispatch_json(&command)?;
        env.new_string(output)
            .map(|s| s.into_raw())
            .map_err(|e| e.to_string())
    })();
    match result {
        Ok(s) => s,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e);
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_lelloman_simpleaiassistant_engine_NativeEngine_destroy(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    if let Ok(mut engines) = engines().lock() {
        engines.remove(&handle);
    }
}
