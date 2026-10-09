package com.smartplug.app.data.local.db

import androidx.room.Entity

/** A user-named, local estimate of a load; it is never presented as a guaranteed identity. */
@Entity(tableName = "load_signatures", primaryKeys = ["deviceId", "name"])
data class LoadSignatureEntity(
    val deviceId: String,
    val name: String,
    val currentA: Double,
    val powerFactor: Double,
)
