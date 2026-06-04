package com.iphc.orangidentifier.domain.model

import android.graphics.RectF

/**
 * Result of detecting a single primate face in an image.
 * Coordinates are in pixels relative to the ORIGINAL image (before any scaling).
 */
data class Detection(
    val boundingBox: RectF,
    val detectorConfidence: Float,
    val topPredictions: List<TopPrediction>,
    val isUnknown: Boolean
)
