package com.msa.btmanager.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A Bluetooth device this app has ever seen. */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val address: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val alias: String,
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

/**
 * A saved RFID/NFC tag readout, Flipper-style:
 * hex block dump + keys recovered by the dictionary attack.
 */
@Entity(tableName = "tag_dumps")
data class TagDump(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uidHex: String,
    val tagType: String,
    val sizeBytes: Int,
    val payload: String,   // decoded NDEF text or empty
    @ColumnInfo(defaultValue = "") val dumpHex: String, // "blockIndex: HEX..." lines
    @ColumnInfo(defaultValue = "") val foundKeys: String, // comma list of recovered keys
    val timestamp: Long = System.currentTimeMillis()
)
