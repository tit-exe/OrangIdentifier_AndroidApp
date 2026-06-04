package com.iphc.orangidentifier.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing one scan saved to the local database.
 * Detections are stored as a JSON string for simplicity.
 */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val imagePath: String,
    val originalImagePath: String = "",
    val sourceType: String,
    val detectionsJson: String,
    val modelVersion: String,
    val durationMs: Long,
    val manuallyAnnotated: Boolean = false
)
