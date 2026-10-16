// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/MainActivity.kt
package com.vivo.wirelink

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher shim. Routes to the flavor's real screen and exits.
 * Keeper flavor disables this component to hide the launcher icon.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (BuildConfig.FLAVOR == "keeper") {
            KeeperActivity::class.java
        } else {
            AdminActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
