// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/WsClient.kt
package com.vivo.wirelink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin WebSocket wrapper with auto-reconnect.
 * Used by both keeper (deviceId in query) and admin (token in query).
 */
class WsClient(
    private val url: String,
    private val onOpen: () -> Unit = {},
    private val onMessage: (JSONObject) -> Unit,
    private val onClosed: () -> Unit = {},
) {

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)   // keeps Render awake while connected
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout — long-lived socket
        .build()

    private var ws: WebSocket? = null
    @Volatile private var running = false
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        running = true
        connect()
    }

    private fun connect() {
        if (!running) return
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (running) onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { onMessage(JSONObject(text)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) {
                    onClosed()
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running) {
                    onClosed()
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        val s = scope ?: return
        reconnectJob?.cancel()
        reconnectJob = s.launch(Dispatchers.IO) {
            delay(2500)
            if (running) connect()
        }
    }

    fun send(json: JSONObject): Boolean = ws?.send(json.toString()) ?: false

    fun stop() {
        running = false
        reconnectJob?.cancel()
        runCatching { ws?.close(1000, "bye") }
        ws = null
    }
}
