package com.iphc.orangidentifier.ml.detector

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.iphc.orangidentifier.data.repository.ModelManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the YOLOv8 TFLite face detector.
 *
 * Input tensor:  [1, 640, 640, 3] float32, pixels normalized to [0.0, 1.0]
 * Output tensor: [1, 5, 8400] float32
 *   - Dimension 1: [cx, cy, w, h, confidence] — 5 values per candidate box
 *   - Dimension 2: 8400 candidate boxes (YOLOv8 anchors)
 *
 * Post-processing:
 *   1. Filter boxes where confidence < CONF_THRESHOLD (0.25)
 *   2. Convert cx/cy/w/h (normalized 0..1) to pixel coordinates
 *   3. Apply NMS with IoU threshold 0.45
 *   4. Return ALL remaining boxes (could be 1..N faces)
 *
 * Thread safety: inference is NOT thread-safe. Run on a single IO dispatcher thread.
 */
@Singleton
class YoloDetector @Inject constructor(
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.40f
        private const val IOU_THRESHOLD  = 0.50f
        private const val NUM_CANDIDATES = 8400
        private const val VALUES_PER_BOX = 5 // cx, cy, w, h, conf
    }

    /**
     * Detects all primate faces in the given bitmap.
     *
     * IMPORTANT: This method is synchronous and CPU/GPU intensive.
     * Always call from a background coroutine (Dispatchers.Default or IO).
     *
     * @param bitmap The full original image at any resolution.
     * @return List of RawDetection with bounding boxes in original image coordinates.
     */
    fun detect(bitmap: Bitmap): List<RawDetection> {
        val inputBuffer  = preprocessForYolo(bitmap)
        val outputBuffer = Array(1) { Array(VALUES_PER_BOX) { FloatArray(NUM_CANDIDATES) } }
        modelManager.getDetectorInterpreter().run(inputBuffer, outputBuffer)
        return parseOutput(outputBuffer[0], bitmap.width, bitmap.height)
    }

    private fun preprocessForYolo(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        if (scaled !== bitmap) scaled.recycle()

        for (pixel in pixels) {
            // YOLOv8 expects [0, 1] normalization (no ImageNet stats)
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f) // G
            buffer.putFloat(((pixel)        and 0xFF) / 255.0f) // B
        }

        buffer.rewind()
        return buffer
    }

    private fun parseOutput(
        output: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<RawDetection> {
        val rawDetections = mutableListOf<RawDetection>()

        // output[row][col]: row = value index (cx=0, cy=1, w=2, h=3, conf=4)
        for (i in 0 until NUM_CANDIDATES) {
            val conf = output[4][i]
            if (conf < CONF_THRESHOLD) continue

            // YOLOv8 outputs normalized center coordinates [0, 1]
            val cx = output[0][i] * imageWidth
            val cy = output[1][i] * imageHeight
            val w  = output[2][i] * imageWidth
            val h  = output[3][i] * imageHeight

            val box = RectF(
                cx - w / 2f,
                cy - h / 2f,
                cx + w / 2f,
                cy + h / 2f
            )

            rawDetections.add(RawDetection(box, conf))
        }

        return NmsProcessor.apply(rawDetections, IOU_THRESHOLD)
    }
}
