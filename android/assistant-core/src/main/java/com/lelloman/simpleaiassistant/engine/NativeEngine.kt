package com.lelloman.simpleaiassistant.engine

/** Narrow synchronous JNI boundary; all asynchronous work belongs to AssistantSession. */
internal object NativeEngine {
    init { System.loadLibrary("simple_assistant") }
    @JvmStatic external fun create(config: String, session: String, archive: String): Long
    @JvmStatic external fun dispatch(handle: Long, command: String): String
    @JvmStatic external fun destroy(handle: Long)
}
