package com.lelloman.simpleaiassistant.provider.simpleai

import com.lelloman.simpleaiassistant.model.StreamEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** Adapt ordered callbacks to a cancellable flow with bounded buffering. */
internal fun cloudStreamSession(
    timeoutMillis: Long = 180_000,
    start: (onData: (String) -> Unit, onFailure: (Throwable) -> Unit) -> Unit,
    cancel: () -> Unit
): Flow<StreamEvent> = flow {
    val channel = Channel<String>(64)
    try {
        withTimeout(timeoutMillis) {
            start({ data ->
                if (data.length > 128 * 1024 || !channel.trySend(data).isSuccess) {
                    channel.close(IllegalStateException("Cloud stream buffer limit exceeded"))
                }
            }, { error -> channel.close(error) })
            val decoder = CloudStreamDecoder()
            for (data in channel) {
                decoder.accept(data).forEach { emit(it) }
                if (decoder.finished) break
            }
        }
    } finally {
        channel.cancel()
        cancel()
    }
}
