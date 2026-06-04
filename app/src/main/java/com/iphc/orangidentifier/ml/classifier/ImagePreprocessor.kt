package com.iphc.orangidentifier.ml.classifier

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Crop helper shared by RunImageInferenceUseCase and AddIndividualViewModel.
 * Preprocessing is handled by MegaDescriptorBackbone (NCHW float32).
 */
object ImagePreprocessor {

    /**
     * Extracts and crops a face region from a full Bitmap using a bounding box.
     * Adds a 10% margin around the box to include forehead and chin context.
     *
     * Caller must recycle() both the returned Bitmap and the source Bitmap when done.
     *
     * @param source The full original Bitmap.
     * @param box    The bounding box in ORIGINAL image pixel coordinates.
     * @return A new Bitmap cropped to the face region (with margin).
     */
    fun cropFace(source: Bitmap, box: RectF): Bitmap {
        val margin = 0.10f
        val w = box.width()
        val h = box.height()

        val left   = maxOf(0f, box.left   - w * margin).toInt()
        val top    = maxOf(0f, box.top    - h * margin).toInt()
        val right  = minOf(source.width.toFloat(),  box.right  + w * margin).toInt()
        val bottom = minOf(source.height.toFloat(), box.bottom + h * margin).toInt()
        val cropW  = (right - left).coerceAtLeast(1)
        val cropH  = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(source, left, top, cropW, cropH)
    }
}
