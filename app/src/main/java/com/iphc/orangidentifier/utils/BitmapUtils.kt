package com.iphc.orangidentifier.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.iphc.orangidentifier.domain.model.Detection
import java.io.File
import java.io.FileOutputStream

object BitmapUtils {

    private const val MAX_DIMENSION = 1920

    /**
     * Decodes a content URI to a Bitmap, applying EXIF rotation correction.
     * Downscales large images to MAX_DIMENSION to save memory.
     */
    fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            val sampleSize = calculateInSampleSize(options.outWidth, options.outHeight, MAX_DIMENSION)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val rotation = ExifUtils.getRotationDegrees(context, uri)
            if (rotation == 0) raw
            else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated !== raw) raw.recycle()
                rotated
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves the raw (un-annotated) bitmap to internal storage for later re-analysis.
     */
    fun saveOriginalBitmap(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "scans")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "original_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }

    /**
     * Saves an annotated version of the bitmap (with bounding boxes drawn) to internal storage.
     * Returns the absolute path of the saved file.
     */
    fun saveAnnotatedBitmap(context: Context, bitmap: Bitmap, detections: List<Detection>): String {
        val annotated = drawDetectionBoxes(bitmap, detections)
        val dir = File(context.filesDir, "scans")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            annotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (annotated !== bitmap) annotated.recycle()
        return file.absolutePath
    }

    /**
     * Draws bounding boxes and labels onto a copy of the bitmap.
     */
    fun drawDetectionBoxes(source: Bitmap, detections: List<Detection>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val boxPaint = Paint().apply {
            color = Color.argb(220, 76, 175, 80)
            style = Paint.Style.STROKE
            strokeWidth = (result.width * 0.004f).coerceAtLeast(3f)
        }
        val bgPaint = Paint().apply {
            color = Color.argb(180, 27, 94, 32)
            style = Paint.Style.FILL
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = (result.width * 0.025f).coerceIn(24f, 48f)
            isFakeBoldText = true
            isAntiAlias = true
        }

        for ((index, det) in detections.withIndex()) {
            canvas.drawRect(det.boundingBox, boxPaint)
            val label = if (det.isUnknown) "Unknown" else det.topPredictions.firstOrNull()?.individualName ?: "?"
            val confStr = if (!det.isUnknown) " %.0f%%".format((det.topPredictions.firstOrNull()?.confidence ?: 0f) * 100) else ""
            val text = "#${index + 1} $label$confStr"
            val textWidth = textPaint.measureText(text)
            val textX = det.boundingBox.left
            val textY = (det.boundingBox.top - 6f).coerceAtLeast(textPaint.textSize + 4f)
            canvas.drawRect(textX, textY - textPaint.textSize - 2f, textX + textWidth + 12f, textY + 4f, bgPaint)
            canvas.drawText(text, textX + 6f, textY, textPaint)
        }
        return result
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longest = maxOf(width, height)
        while (longest / (sampleSize * 2) > maxDimension) sampleSize *= 2
        return sampleSize
    }
}
