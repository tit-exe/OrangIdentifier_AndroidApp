package com.iphc.orangidentifier.ml.detector

import android.graphics.RectF

/**
 * Non-Maximum Suppression for YOLO output.
 * Removes overlapping bounding boxes, keeping only the most confident one
 * for each detected face region.
 */
object NmsProcessor {

    /**
     * Applies NMS to a list of raw detections.
     *
     * @param detections   All boxes above the confidence threshold.
     * @param iouThreshold Maximum Intersection-over-Union before a box is suppressed.
     *                     Use 0.45 (matches training configuration).
     * @return Filtered list with overlapping boxes removed.
     */
    fun apply(detections: List<RawDetection>, iouThreshold: Float = 0.45f): List<RawDetection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<RawDetection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best.box, it.box) > iouThreshold }
        }

        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left, b.left)
        val interTop    = maxOf(a.top, b.top)
        val interRight  = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (interArea == 0f) return 0f

        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        return interArea / (aArea + bArea - interArea)
    }
}
