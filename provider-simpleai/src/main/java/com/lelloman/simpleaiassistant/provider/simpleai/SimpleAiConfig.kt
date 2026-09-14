package com.lelloman.simpleaiassistant.provider.simpleai

/**
 * Configuration for the SimpleAI LLM provider.
 *
 * SimpleAI communicates with the SimpleAI Android app via AIDL.
 * SimpleAI owns authentication; calling apps need only local user approval.
 *
 * @param authTokenProvider Legacy compatibility parameter; ignored.
 */
class SimpleAiConfig(
    val authTokenProvider: () -> String = { "" }
)
