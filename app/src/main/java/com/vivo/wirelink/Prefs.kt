// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/Prefs.kt
package com.vivo.wirelink

import android.content.Context
import java.util.UUID

/**
 * Tiny wrapper over SharedPreferences.
 * One file, three keys — backend url, device id, admin token.
 */
object Prefs {

    private const val FILE = "wirelink"
    private const val K_URL = "backend_url"
    private const val K_DEVICE = "device_id"
    private const val K_TOKEN = "admin_token"

    private fun sp(c: Context) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- backend url ---
    fun backendUrl(c: Context): String? =
        sp(c).getString(K_URL, null)

    fun setBackendUrl(c: Context, url: String) =
        sp(c).edit().putString(K_URL, url).apply()

    // --- device id (generated once, stable per install) ---
    fun deviceId(c: Context): String {
        val existing = sp(c).getString(K_DEVICE, null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
            .replace("-", "")
            .take(12)
        sp(c).edit().putString(K_DEVICE, fresh).apply()
        return fresh
    }

    // --- admin token (set on successful login, cleared on sign-out) ---
    fun adminToken(c: Context): String? =
        sp(c).getString(K_TOKEN, null)

    fun setAdminToken(c: Context, t: String?) {
        sp(c).edit().apply {
            if (t == null) remove(K_TOKEN) else putString(K_TOKEN, t)
        }.apply()
    }
}
