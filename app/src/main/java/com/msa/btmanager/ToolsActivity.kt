package com.msa.btmanager

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Flipper-style hub: every tool opens from here. */
class ToolsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)

        findViewById<Button>(R.id.btnBluetooth).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnNfc).setOnClickListener {
            startActivity(Intent(this, TagActivity::class.java))
        }
        findViewById<Button>(R.id.btnArchive).setOnClickListener {
            startActivity(Intent(this, DataActivity::class.java))
        }
        findViewById<Button>(R.id.btnLF).setOnClickListener {
            Toast.makeText(this, "No 125 kHz radio in phones — needs dedicated hardware", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnSubGhz).setOnClickListener {
            Toast.makeText(this, "No Sub-GHz radio in phones — needs dedicated hardware", Toast.LENGTH_LONG).show()
        }
    }
}
