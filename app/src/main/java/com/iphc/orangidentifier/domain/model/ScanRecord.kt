package com.iphc.orangidentifier.domain.model

/**
 * A complete scan event saved to the history database.
 */
data class ScanRecord(
    val id: Long = 0,
    val timestamp: Long,
    val imagePath: String,
    val originalImagePath: String = "",
    val sourceType: SourceType,
    val detections: List<Detection>,
    val modelVersion: String,
    val durationMs: Long,
    val manuallyAnnotated: Boolean = false
) {
    enum class SourceType { CAMERA, GALLERY }
}
