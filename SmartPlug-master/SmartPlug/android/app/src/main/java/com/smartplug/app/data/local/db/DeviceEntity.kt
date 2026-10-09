package com.smartplug.app.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val staMac: String,
    val displayName: String,
    val lanIp: String?,
    val integrationMode: String,
    val serverId: String?,
    val serverHost: String? = null,
    val serverPort: Int = 80,
    /** The pairing AP's unit id (SSID "SP-<unitId>"), so a re-scan can hide already-paired units. */
    val apUnitId: String? = null,
)
