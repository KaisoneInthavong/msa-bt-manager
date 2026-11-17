package com.msa.btmanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A Bluetooth device this app has ever seen. */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val address: String,
    val name: String,
    val isMsa: Boolean,
    val lastSeen: Long,
    val connectionCount: Int = 0
)

/** One line of communication with a device. */
@Entity(tableName = "comm_log")
data class CommLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val direction: String, // "SENT" or "RECEIVED"
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
