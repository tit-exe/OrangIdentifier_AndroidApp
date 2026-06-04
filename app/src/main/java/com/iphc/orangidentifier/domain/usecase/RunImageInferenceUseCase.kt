package com.iphc.orangidentifier.domain.usecase

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import com.iphc.orangidentifier.data.repository.ModelManager
import com.iphc.orangidentifier.domain.model.Detection
import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.domain.repository.ScanRepository
import com.iphc.orangidentifier.ml.classifier.ImagePreprocessor
import com.iphc.orangidentifier.ml.classifier.MegaDescriptorBackbone
import com.iphc.orangidentifier.ml.detector.YoloDetector
import com.iphc.orangidentifier.utils.BitmapUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class InferenceResult {
    data class Success(val detections: List<Detection>, val recordId: Long) : InferenceResult()
    object NoFaceFound : InferenceResult()
    data class Failure(val message: String) : InferenceResult()
}

/**
 * Orchestrates the full image inference pipeline:
 * 1. Decode URI to Bitmap (fix EXIF orientation)
 * 2. Run YOLOv8 to detect ALL faces (sequential — TFLite Interpreter is NOT thread-safe)
 * 3. For each face: crop → MegaDescriptorBackbone → Top-K  (sequential .map, never async/awaitAll)
 * 4. Recycle all intermediate Bitmaps immediately
 * 5. Save annotated result image to internal storage
 * 6. Persist ScanRecord to Room
 * 7. Return list of Detection objects for the UI
 */
class RunImageInferenceUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val yoloDetector: YoloDetector,
    private val backbone: MegaDescriptorBackbone,
    private val scanRepository: ScanRepository,
    private val modelManager: ModelManager
) {
    suspend fun execute(
        imageUri: Uri,
        sourceType: ScanRecord.SourceType
    ): InferenceResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val fullBitmap = BitmapUtils.decodeUri(context, imageUri)
            ?: return@withContext InferenceResult.Failure("Could not decode image. Try again.")

        try {
            val rawDetections = yoloDetector.detect(fullBitmap)

            if (rawDetections.isEmpty()) {
                return@withContext InferenceResult.NoFaceFound
            }

            // Sequential map — TFLite Interpreter is NOT thread-safe.
            // Never replace this with async/awaitAll or parallel coroutines.
            val detections = rawDetections.map { raw ->
                val crop = ImagePreprocessor.cropFace(fullBitmap, raw.box)
                // backbone.classify() recycles crop internally — do NOT use crop after this call
                val result = backbone.classify(crop)
                Detection(
                    boundingBox        = raw.box,
                    detectorConfidence = raw.confidence,
                    topPredictions     = result.topPredictions,
                    isUnknown          = result.isUnknown
                )
            }

            val originalPath  = BitmapUtils.saveOriginalBitmap(context, fullBitmap)
            val annotatedPath = BitmapUtils.saveAnnotatedBitmap(context, fullBitmap, detections)
            val duration      = System.currentTimeMillis() - startTime

            val record = ScanRecord(
                timestamp         = System.currentTimeMillis(),
                imagePath         = annotatedPath,
                originalImagePath = originalPath,
                sourceType        = sourceType,
                detections        = detections,
                modelVersion      = modelManager.activeClassifierVersion(),
                durationMs        = duration
            )
            val recordId = scanRepository.saveScan(record)

            InferenceResult.Success(detections, recordId)

        } catch (e: CancellationException) {
            throw e   // never swallow coroutine cancellation
        } catch (e: Exception) {
            InferenceResult.Failure(e.message ?: "Inference failed")
        } finally {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
        }
    }

    /**
     * Runs identification on a fixed list of manually-annotated boxes.
     * Skips YOLO entirely — boxes come from the user.
     * Caller must save the result via scanRepository.updateScan().
     */
    suspend fun runOnCrops(
        originalImagePath: String,
        boxes: List<RectF>
    ): List<Detection> = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeFile(originalImagePath)
            ?: throw IllegalStateException("Cannot decode image: $originalImagePath")
        try {
            boxes.map { box ->
                val crop   = ImagePreprocessor.cropFace(bmp, box)
                val result = backbone.classify(crop)
                Detection(
                    boundingBox        = box,
                    detectorConfidence = 1f,
                    topPredictions     = result.topPredictions,
                    isUnknown          = result.isUnknown
                )
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }
}
