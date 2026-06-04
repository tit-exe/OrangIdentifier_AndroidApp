package com.iphc.orangidentifier.ml.detector

import android.graphics.RectF

/**
 * Raw output from the YOLO detector before classification.
 */
data class RawDetection(
    val box: RectF,
    val confidence: Float
)
