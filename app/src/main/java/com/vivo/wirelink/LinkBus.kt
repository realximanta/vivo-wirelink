// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/LinkBus.kt
package com.vivo.wirelink

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * LinkBus — one-way channel from NotificationRelay -> KeeperService.
 * No context, no lifecycle, just a singleton SharedFlow.
 *
 * NotificationRelay emits JSONObject frames. KeeperService collects them
 * and ships them over the WS to the backend (and Telegram).
 */
object LinkBus {

    private val _events = MutableSharedFlow<JSONObject>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    /**
     * Non-suspending emit. Safe to call from any thread.
     * Returns false only if the buffer is full AND no subscribers are attached —
     * in practice SharedFlow with DROP_OLDEST never fails.
     */
    fun push(frame: JSONObject): Boolean = _events.tryEmit(frame)
}
