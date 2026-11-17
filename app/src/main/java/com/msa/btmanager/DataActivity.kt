package com.msa.btmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.msa.btmanager.data.AppDatabase
import com.msa.btmanager.data.CommLogEntry
import com.msa.btmanager.data.DeviceEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataActivity : AppCompatActivity() {

    private enum class Mode { DEVICES, LOG }

    private val db by lazy { AppDatabase.get(this) }
    private var mode = Mode.DEVICES
    private var deviceRows: List<DeviceEntity> = emptyList()

    private lateinit var tvTitle: TextView
    private lateinit var listData: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data)

        tvTitle = findViewById(R.id.tvTitle)
        listData = findViewById(R.id.listData)

        findViewById<Button>(R.id.btnDevices).setOnClickListener {
            mode = Mode.DEVICES
            load()
        }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            mode = Mode.LOG
            load()
        }

        listData.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (mode == Mode.DEVICES) showDeviceOptions(position)
        }

        load()
    }

    private fun load() {
        Thread {
            when (mode) {
                Mode.DEVICES -> {
                    deviceRows = db.deviceDao().getAll()
                    val lines = deviceRows.map { d ->
                        val name = d.alias.ifBlank { d.name }
                        val flag = if (d.isMsa) "MSA" else "PAIRED"
                        "$name\n$d.address • connections: ${d.connectionCount} • $flag"
                    }
                    runOnUiThread {
                        tvTitle.text = "Devices (${deviceRows.size})"
                        listData.adapter =
                            ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
                        if (lines.isEmpty())
                            Toast.makeText(this, "No devices saved yet", Toast.LENGTH_SHORT).show()
                    }
                }
                Mode.LOG -> {
                    val entries = db.commLogDao().recent(200)
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val lines = entries.map { e ->
                        "[${e.direction}] ${e.deviceAddress}\n${fmt.format(Date(e.timestamp))} : ${e.message}"
                    }
                    runOnUiThread {
                        tvTitle.text = "Communication Log (${entries.size})"
                        listData.adapter =
                            ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
                        if (lines.isEmpty())
                            Toast.makeText(this, "No communication logged yet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showDeviceOptions(index: Int) {
        val d = deviceRows.getOrNull(index) ?: return
        val name = d.alias.ifBlank { d.name }
        val actions = arrayOf("Rename", "Copy name", "Copy address", "Delete device")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameDialog(d)
                    1 -> copyToClipboard("device name", name)
                    2 -> copyToClipboard("device address", d.address)
                    3 -> confirmDelete(d)
                }
            }
            .show()
    }

    private fun showRenameDialog(d: DeviceEntity) {
        val input = EditText(this)
        input.setText(d.alias)
        input.hint = "Custom name (empty = original)"
        AlertDialog.Builder(this)
            .setTitle("Rename ${d.name}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newAlias = input.text.toString().trim()
                Thread {
                    db.deviceDao().rename(d.address, newAlias)
                    runOnUiThread { load() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(d: DeviceEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Remove ${d.alias.ifBlank { d.name }} (${d.address}) from memory?")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    db.deviceDao().delete(d.address)
                    runOnUiThread { load() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
    }
}
