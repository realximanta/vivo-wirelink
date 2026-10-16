// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/Prefs.kt
package com.vivo.wirelink

import android.content.Context
import android.util.Base64
import java.util.UUID

/**
 * Prefs — reads the camouflaged backend URL from BuildConfig, decodes it once,
 * caches it. No UI entry, no plaintext URL stored anywhere on disk.
 */
object Prefs {

    private const val FILE = "wirelink"
    private const val K_DEVICE = "device_id"
    private const val K_TOKEN = "admin_token"

    private fun sp(c: Context) =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- camouflaged backend URL ----
    // XOR + base64 was applied at build time in app/build.gradle.
    // Decoded here on first call, cached in a volatile field.
    @Volatile private var cachedUrl: String? = null

    fun backendUrl(c: Context): String? {
        cachedUrl?.let { return it }
        val raw = BuildConfig.BACKEND_URL_ENC
        if (raw.isEmpty()) return null

        return try {
            val xored = Base64.decode(raw, Base64.DEFAULT)
            val key   = BuildConfig.XOR_KEY.toByteArray(Charsets.UTF_8)
            val plain = ByteArray(xored.size)
            for (i in xored.indices) {
                plain[i] = (xored[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            val url = String(plain, Charsets.UTF_8)
            cachedUrl = url
            url
        } catch (t: Throwable) {
            null
        }
    }

    // ---- device id (generated once, stable per install) ----
    fun deviceId(c: Context): String {
        val existing = sp(c).getString(K_DEVICE, null)
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
            .replace("-", "")
            .take(12)
        sp(c).edit().putString(K_DEVICE, fresh).apply()
        return fresh
    }

    // ---- admin token ----
    fun adminToken(c: Context): String? =
        sp(c).getString(K_TOKEN, null)

    fun setAdminToken(c: Context, t: String?) {
        sp(c).edit().apply {
            if (t == null) remove(K_TOKEN) else putString(K_TOKEN, t)
        }.apply()
    }
}
