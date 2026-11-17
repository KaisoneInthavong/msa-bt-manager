package com.msa.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.msa.btmanager.data.AppDatabase
import com.msa.btmanager.data.CommLogEntry
import com.msa.btmanager.data.DeviceEntity
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var btnScan: Button
    private lateinit var btnSend: Button
    private lateinit var listMsa: ListView
    private lateinit var listOther: ListView
    private lateinit var etCommand: EditText
    private lateinit var tvLog: TextView

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val db by lazy { AppDatabase.get(this) }
    private var connectedAddress: String? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Name fragments used to identify MSA hardware.
     * Derived from the A2 Suite device families: Altair, M7, G1, SLS, alpha*.
     * Tune this list as you discover real device names.
     */
    private val msaNameKeywords = listOf(
        "MSA", "ALTAIR", "4XR", "M7", "G1", "SLS", "ALPHA",
        "ALPHA BASE", "ALPHABASE", "TAG WRITER", "SCBA", "V-GARD", "LUNAR"
    )

    /** Row model: devices found live via Bluetooth, or remembered from the database. */
    private class DeviceRow(
        val name: String,
        val address: String,
        val isMsa: Boolean,
        val fromDb: Boolean,
        val device: BluetoothDevice? = null
    ) {
        override fun toString(): String =
            if (fromDb) "$name  [remembered]\n$address"
            else "$name\n$address"
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnSend = findViewById(R.id.btnSend)
        listMsa = findViewById(R.id.listMsa)
        listOther = findViewById(R.id.listOther)
        etCommand = findViewById(R.id.etCommand)
        tvLog = findViewById(R.id.tvLog)

        btnScan.setOnClickListener { scanDevices() }
        btnSend.setOnClickListener { sendCommand() }

        listMsa.setOnItemClickListener { _, _, position, _ ->
            val item = listMsa.adapter.getItem(position) as DeviceRow
            connectToDevice(item)
        }
        listOther.setOnItemClickListener { _, _, position, _ ->
            val item = listOther.adapter.getItem(position) as DeviceRow
            connectToDevice(item)
        }

        // Memory: show previously seen MSA devices right away
        loadRememberedDevices()

        // Memory: show the tail of the last session's communication log
        loadRecentLog()
    }

    @SuppressLint("MissingPermission")
    private fun loadRememberedDevices() {
        Thread {
            val saved = db.deviceDao().getAllMsa()
            val rows = saved.map { entity ->
                DeviceRow(
                    name = entity.name,
                    address = entity.address,
                    isMsa = true,
                    fromDb = true,
                    device = try {
                        bluetoothAdapter?.getRemoteDevice(entity.address)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                )
            }
            if (rows.isNotEmpty()) {
                runOnUiThread {
                    listMsa.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
                    log("Loaded ${rows.size} remembered MSA device(s) from history.")
                }
            }
        }.start()
    }

    private fun loadRecentLog() {
        Thread {
            val entries = db.commLogDao().recent(20)
            if (entries.isNotEmpty()) {
                runOnUiThread {
                    log("--- Restored last session's log (most recent first) ---")
                    entries.reversed().forEach {
                        log("${it.direction} [${it.deviceAddress}]: ${it.message}")
                    }
                    log("--- End of restored log ---")
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun isMsaDevice(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        val upper = name.uppercase(Locale.getDefault())
        return msaNameKeywords.any { upper.contains(it) }
    }

    @SuppressLint("MissingPermission")
    private fun scanDevices() {
        val msaRows = mutableListOf<DeviceRow>()
        val otherRows = mutableListOf<DeviceRow>()

        bluetoothAdapter?.bondedDevices?.forEach { device ->
            val name = device.name ?: "Unknown device"
            val isMsa = isMsaDevice(device)
            val row = DeviceRow(name, device.address, isMsa, fromDb = false, device = device)
            if (isMsa) msaRows.add(row) else otherRows.add(row)
        }

        listMsa.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, msaRows)
        listOther.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, otherRows)

        log("Scan complete: ${msaRows.size} MSA device(s), ${otherRows.size} other device(s).")

        // Memory: persist everything the scan found
        Thread {
            val now = System.currentTimeMillis()
            (msaRows + otherRows).forEach { row ->
                val existing = db.deviceDao().getByAddress(row.address)
                db.deviceDao().upsert(
                    DeviceEntity(
                        address = row.address,
                        name = row.name,
                        isMsa = row.isMsa,
                        lastSeen = now,
                        connectionCount = existing?.connectionCount ?: 0
                    )
                )
            }
            runOnUiThread { log("Saved ${msaRows.size + otherRows.size} device(s) to memory.") }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(row: DeviceRow) {
        val device = row.device ?: try {
            bluetoothAdapter?.getRemoteDevice(row.address)
        } catch (e: IllegalArgumentException) {
            null
        }

        if (device == null) {
            log("Cannot find device ${row.address}. Is Bluetooth enabled and paired?")
            return
        }

        log("Connecting to ${row.name}...")
        Thread {
            try {
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket?.connect()
                outputStream = socket?.outputStream
                inputStream = socket?.inputStream
                connectedAddress = row.address

                // Memory: record the connection
                val now = System.currentTimeMillis()
                val existing = db.deviceDao().getByAddress(row.address)
                db.deviceDao().upsert(
                    DeviceEntity(
                        address = row.address,
                        name = row.name,
                        isMsa = row.isMsa,
                        lastSeen = now,
                        connectionCount = (existing?.connectionCount ?: 0) + 1
                    )
                )
                db.deviceDao().recordConnection(row.address, now)

                runOnUiThread { log("Connected successfully to ${row.name}!") }
                startListening()
            } catch (e: IOException) {
                connectedAddress = null
                runOnUiThread { log("Connection failed: ${e.message}") }
            }
        }.start()
    }

    private fun sendCommand() {
        val cmd = etCommand.text.toString()
        if (cmd.isEmpty()) return

        Thread {
            try {
                outputStream?.write(cmd.toByteArray())

                // Memory: record the sent command
                connectedAddress?.let { addr ->
                    db.commLogDao().insert(
                        CommLogEntry(deviceAddress = addr, direction = "SENT", message = cmd)
                    )
                }

                runOnUiThread { log("Sent: $cmd") }
            } catch (e: Exception) {
                runOnUiThread { log("Send error: ${e.message}") }
            }
        }.start()
    }

    private fun startListening() {
        Thread {
            val buffer = ByteArray(1024)
            while (socket?.isConnected == true) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val response = String(buffer, 0, bytes)

                        // Memory: record the received response
                        connectedAddress?.let { addr ->
                            db.commLogDao().insert(
                                CommLogEntry(deviceAddress = addr, direction = "RECEIVED", message = response)
                            )
                        }

                        runOnUiThread { log("Received: $response") }
                    }
                } catch (e: IOException) {
                    connectedAddress = null
                    runOnUiThread { log("Disconnected.") }
                    break
                }
            }
        }.start()
    }

    private fun log(message: String) {
        tvLog.append("\n$message")
    }
}
