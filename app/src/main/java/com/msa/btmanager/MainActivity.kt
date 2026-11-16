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
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var btnScan: Button
    private lateinit var btnSend: Button
    private lateinit var deviceList: ListView
    private lateinit var etCommand: EditText
    private lateinit var tvLog: TextView

    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnSend = findViewById(R.id.btnSend)
        deviceList = findViewById(R.id.deviceList)
        etCommand = findViewById(R.id.etCommand)
        tvLog = findViewById(R.id.tvLog)

        btnScan.setOnClickListener { scanDevices() }
        btnSend.setOnClickListener { sendCommand() }
    }

    @SuppressLint("MissingPermission")
    private fun scanDevices() {
        val pairedDevices = bluetoothAdapter?.bondedDevices
        val deviceNames = mutableListOf<String>()
        val deviceObjects = mutableListOf<BluetoothDevice>()

        pairedDevices?.forEach { device ->
            deviceNames.add("${device.name}\n${device.address}")
            deviceObjects.add(device)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceList.adapter = adapter

        deviceList.setOnItemClickListener { _, _, position, _ ->
            connectToDevice(deviceObjects[position])
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        log("Connecting to ${device.name}...")

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
