package com.msa.btmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices WHERE address = :address")
    fun getByAddress(address: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE isMsa = 1 ORDER BY lastSeen DESC")
    fun getAllMsa(): List<DeviceEntity>

    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAll(): List<DeviceEntity>

    @Query("UPDATE devices SET connectionCount = connectionCount + 1, lastSeen = :now WHERE address = :address")
    fun recordConnection(address: String, now: Long)
}

@Dao
interface CommLogDao {
    @Insert
    fun insert(entry: CommLogEntry)

    @Query("SELECT * FROM comm_log ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): List<CommLogEntry>

    @Query("SELECT * FROM comm_log WHERE deviceAddress = :address ORDER BY timestamp DESC")
    fun forDevice(address: String): List<CommLogEntry>
}
