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
            connectToDevice(item.device)
        }
        listOther.setOnItemClickListener { _, _, position, _ ->
            val item = listOther.adapter.getItem(position) as DeviceRow
            connectToDevice(item.device)
        }
    }

    /** Simple row model so we can carry the BluetoothDevice with each list entry. */
    private class DeviceRow(val device: BluetoothDevice) {
        override fun toString(): String {
            @SuppressLint("MissingPermission")
            val name = device.name ?: "Unknown device"
            @SuppressLint("MissingPermission")
            return "$name\n${device.address}"
        }
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
            if (isMsaDevice(device)) msaRows.add(DeviceRow(device)) else otherRows.add(DeviceRow(device))
        }

        listMsa.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, msaRows)
        listOther.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, otherRows)

        log("Scan complete: ${msaRows.size} MSA device(s), ${otherRows.size} other device(s).")
        if (msaRows.isEmpty()) {
            log("No MSA devices found. If your device is paired but missing, tell me its exact name and I will add it to the filter.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        log("Connecting to ${device.name ?: "Unknown device"}...")

        Thread {
            try {
                socket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket?.connect()
                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                runOnUiThread { log("Connected successfully!") }
                startListening()
            } catch (e: IOException) {
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
                        runOnUiThread { log("Received: $response") }
                    }
                } catch (e: IOException) {
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
