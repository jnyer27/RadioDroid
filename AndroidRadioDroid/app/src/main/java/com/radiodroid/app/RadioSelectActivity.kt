package com.radiodroid.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.radiodroid.app.bridge.ChirpBridge
import com.radiodroid.app.model.RadioInfo

/**
 * Launch screen: user picks a radio vendor/model from the full CHIRP driver list,
 * then chooses a connection method (USB OTG or BLE).
 *
 * TODO Phase 1 implementation:
 *  - Load radio list from ChirpBridge.getRadioList()
 *  - Show searchable RecyclerView grouped by vendor
 *  - Save last-used radio to SharedPreferences
 *  - "Connect USB" and "Connect BLE" buttons
 */
class RadioSelectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Temporary: launch MainActivity directly with a placeholder radio
        // until RadioSelectActivity UI is fully implemented
        Toast.makeText(this, "RadioDroid — select radio coming soon", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
