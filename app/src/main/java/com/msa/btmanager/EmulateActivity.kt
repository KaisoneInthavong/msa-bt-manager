package com.msa.btmanager

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Turns the phone into an NFC tag that another reader can tap and read.
 *
 * Uses Host Card Emulation (HCE) with the NFC Forum Type 4 Tag application,
 * so a second phone or an NFC reader sees a normal NDEF tag.
 */
class EmulateActivity : AppCompatActivity() {

    private lateinit var etEmulateText: EditText
    private lateinit var tvEmulateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulate)

        etEmulateText = findViewById(R.id.etEmulateText)
        tvEmulateStatus = findViewById(R.id.tvEmulateStatus)

        val uiPrefs = getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        etEmulateText.setText(uiPrefs.getString(KEY_TEXT, "") ?: "")

        findViewById<Button>(R.id.btnStartEmulation).setOnClickListener { startEmulation() }
        findViewById<Button>(R.id.btnStopEmulation).setOnClickListener { stopEmulation() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onPause() {
        getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT, etEmulateText.text.toString())
            .apply()
        super.onPause()
    }

    private fun nfcAdapter(): NfcAdapter? = NfcAdapter.getDefaultAdapter(this)

    private fun startEmulation() {
        val text = etEmulateText.text.toString().trim()
        if (text.isEmpty()) {
            tvEmulateStatus.text = "Type the content you want the tag to carry first."
            return
        }

        val adapter = nfcAdapter()
        if (adapter == null) {
            tvEmulateStatus.text = "This phone has no NFC hardware, so it cannot emulate a tag."
            return
        }
        if (!adapter.isEnabled) {
            tvEmulateStatus.text = "NFC is off. Turn NFC on in Settings, then tap Start again."
            return
        }

        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", text))).toByteArray()
        if (message.size > NdefEmulator.MAX_NDEF_BYTES) {
            tvEmulateStatus.text =
                "Too long to emulate (${message.size} B). Keep the text under about " +
                        "${NdefEmulator.MAX_NDEF_BYTES - 8} bytes."
            return
        }

        NdefEmulator.save(this, message)
        setEmulationServiceEnabled(true)
        updateStatus()
    }

    private fun stopEmulation() {
        NdefEmulator.clear(this)
        setEmulationServiceEnabled(false)
        updateStatus()
    }

    private fun setEmulationServiceEnabled(enabled: Boolean) {
        val component = ComponentName(this, NdefEmulatorService::class.java)
        packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun isEmulationServiceEnabled(): Boolean {
        val component = ComponentName(this, NdefEmulatorService::class.java)
        return packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    private fun updateStatus() {
        val adapter = nfcAdapter()
        val payload = NdefEmulator.load(this)
        val active = isEmulationServiceEnabled() && payload != null

        val readerState = when {
            adapter == null -> "no NFC hardware"
            adapter.isEnabled -> "NFC on"
            else -> "NFC off"
        }

        tvEmulateStatus.text = buildString {
            append("Reader: $readerState\n")
            if (active) {
                append("Emulating a ${payload!!.size} byte NDEF tag.\n")
                append("Hold the back of another phone against the back of this phone to read it.")
            } else {
                append("Not emulating.")
            }
        }
    }

    companion object {
        private const val PREFS_UI = "emulator_ui"
        private const val KEY_TEXT = "text"
    }
}
