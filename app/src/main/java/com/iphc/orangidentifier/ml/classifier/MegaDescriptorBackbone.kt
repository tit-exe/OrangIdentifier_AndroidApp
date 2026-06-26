package com.iphc.orangidentifier.ml.classifier

import android.graphics.Bitmap
import android.util.Log
import com.iphc.orangidentifier.data.repository.ModelManager
import com.iphc.orangidentifier.domain.model.TopPrediction
import com.iphc.orangidentifier.ml.EmbeddingUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal embedding backbone for primate face identification.
 *
 * Works with both:
 *   V2 — ResNet50 backbone, 2048-dim, ImageNet normalization
 *          mean=[0.485, 0.456, 0.406]  std=[0.229, 0.224, 0.225]
 *   V3 — MegaDescriptor-T-224 backbone, 768-dim, symmetric normalization
 *          mean=[0.5, 0.5, 0.5]  std=[0.5, 0.5, 0.5]
 *
 * PREPROCESSING DETECTION
 * -----------------------
 * The training script (V2_5_train_arcface.py) writes "normalization": "L2" in the
 * gallery JSON — that refers to the OUTPUT embedding normalization, not the input
 * image preprocessing. The backbone used (V2 vs V3) is detected by embedding_dim:
 *   - 768  → MegaDescriptor → mean/std = [0.5, 0.5, 0.5]
 *   - other → ResNet50      → ImageNet means/stds
 *
 * Thread safety: NOT thread-safe (TFLite Interpreter is single-threaded).
 * Always called sequentially from a single background coroutine.
 *
 * Both classify() and embed() recycle croppedFace internally — do NOT use the
 * bitmap after calling either method.
 */
@Singleton
class MegaDescriptorBackbone @Inject constructor(
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG          = "MegaDescBackbone"
        private const val TOP_K        = 3
        private const val INPUT_SIZE   = 224
        private const val FLOAT_BYTES  = 4
        private const val CHANNELS     = 3

        // ImageNet normalization — V2 / ResNet50
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD  = floatArrayOf(0.229f, 0.224f, 0.225f)

        // MegaDescriptor-T-224 normalization — V3
        private val MEGADESC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val MEGADESC_STD  = floatArrayOf(0.5f, 0.5f, 0.5f)

        /**
         * Minimum margin required between the #1 and #2 scores for a positive ID.
         *
         * When scores are compressed (e.g. Berunay 84.5%, Farida 84.3%, Hanau 83.8%)
         * the model has no real conviction — it's saying "all known orangutans look
         * roughly equally like this". A margin below this threshold means the query
         * is closer to a generic "orangutan shape" than to any specific individual,
         * so we reject it as Unknown regardless of the absolute score.
         *
         * Typical values observed:
         *   - Internet photo of unknown orang → margin ~0.002–0.005  → rejected ✓
         *   - Good field photo of known individual → margin ~0.10–0.35  → accepted ✓
         *   - Borderline field photo → margin ~0.05–0.10  → marginal zone
         *
         * Set conservatively at 0.08 to avoid false positives on BOS individuals
         * that have few training crops and tend to produce compressed similarities.
         */
        private const val MARGIN_THRESHOLD = 0.08f
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Full classification pipeline.
     * Recycles [croppedFace] internally.
     */
    fun classify(croppedFace: Bitmap): ClassificationResult {
        val gallery      = modelManager.getEmbeddings()
        val embedding    = runBackbone(croppedFace, gallery.embeddingDim)

        val ranked = gallery.prototypes
            .map { proto ->
                // V6 scoring: max cosine similarity over all training exemplars.
                // For V3/user-added individuals proto.exemplars = [anchor] so this
                // degrades cleanly to a single dot product — no special case needed.
                val exemplarSim = proto.exemplars.maxOf { EmbeddingUtils.dotProduct(embedding, it) }
                // Field embedding (user field photos) acts as extra exemplar — can only improve.
                val fieldSim    = proto.fieldEmbedding?.let { EmbeddingUtils.dotProduct(embedding, it) }
                    ?: Float.NEGATIVE_INFINITY
                Pair(proto, maxOf(exemplarSim, fieldSim))
            }
            .sortedByDescending { it.second }

        val topK = ranked.take(TOP_K).mapIndexed { rank, (proto, score) ->
            TopPrediction(
                rank           = rank + 1,
                individualName = proto.name,
                confidence     = score.coerceIn(0f, 1f),
                classIndex     = proto.classIndex
            )
        }

        val bestScore   = ranked.getOrNull(0)?.second ?: 0f
        val secondScore = ranked.getOrNull(1)?.second ?: 0f
        val margin      = bestScore - secondScore

        // Two independent rejection conditions:
        //   1. Absolute threshold: score too low → clearly not in gallery
        //   2. Margin threshold: top-2 scores too close → model is uncertain,
        //      likely a generic "orangutan" embedding, not a specific individual
        val isUnknown = bestScore < modelManager.getUnknownThreshold()
                || margin < MARGIN_THRESHOLD

        if (isUnknown) {
            Log.d(TAG, "Unknown — best=${"%.3f".format(bestScore)} " +
                    "margin=${"%.3f".format(margin)} " +
                    "threshold=${gallery.unknownThreshold} " +
                    "marginThreshold=$MARGIN_THRESHOLD")
        } else {
            Log.d(TAG, "Identified: ${ranked.firstOrNull()?.first?.name} " +
                    "(score=${"%.3f".format(bestScore)} margin=${"%.3f".format(margin)})")
        }

        return ClassificationResult(
            topPredictions = topK,
            isUnknown      = isUnknown,
            rawLogits      = embedding
        )
    }

    /**
     * Bare embedding — returns the L2-normalised feature vector without classification.
     * Used by the Add Individual flow to compute per-crop embeddings.
     * Recycles [croppedFace] internally.
     */
    fun embed(croppedFace: Bitmap): FloatArray {
        val embeddingDim = modelManager.getEmbeddings().embeddingDim
        return runBackbone(croppedFace, embeddingDim)
    }

    // ── Core backbone run ─────────────────────────────────────────────────────

    /**
     * Runs the TFLite backbone: preprocess → run → L2-normalize.
     * Recycles [bitmap] internally.
     */
    private fun runBackbone(bitmap: Bitmap, embeddingDim: Int): FloatArray {
        val inputBuffer  = preprocess(bitmap, embeddingDim)
        bitmap.recycle()
        val outputBuffer = Array(1) { FloatArray(embeddingDim) }
        modelManager.getClassifierInterpreter().run(inputBuffer, outputBuffer)
        return EmbeddingUtils.l2Normalize(outputBuffer[0])
    }

    // ── Preprocessing ─────────────────────────────────────────────────────────

    /**
     * Determines preprocessing from embedding_dim:
     *   768  → MegaDescriptor normalization [0.5, 0.5, 0.5]
     *   else → ImageNet normalization
     *
     * This avoids misreading the gallery "normalization" field, which the training
     * script uses to mean "output L2-normalization applied", not input preprocessing.
     */
    private fun preprocess(bitmap: Bitmap, embeddingDim: Int): ByteBuffer {
        val (mean, std) = if (embeddingDim == 768) {
            Pair(MEGADESC_MEAN, MEGADESC_STD)
        } else {
            Pair(IMAGENET_MEAN, IMAGENET_STD)
        }
        return normalizeToBuffer(bitmap, mean, std)
    }

    /**
     * Writes pixels in NCHW format: shape [1, 3, 224, 224].
     * All R values first, then all G, then all B — NOT interleaved NHWC.
     */
    private fun normalizeToBuffer(bitmap: Bitmap, mean: FloatArray, std: FloatArray): ByteBuffer {
        val scaled     = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val bufferSize = 1 * CHANNELS * INPUT_SIZE * INPUT_SIZE * FLOAT_BYTES
        val buffer     = ByteBuffer.allocateDirect(bufferSize).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // NCHW: write entire R plane, then G plane, then B plane
        for (pixel in pixels) buffer.putFloat((((pixel shr 16) and 0xFF) / 255.0f - mean[0]) / std[0])
        for (pixel in pixels) buffer.putFloat((((pixel shr 8)  and 0xFF) / 255.0f - mean[1]) / std[1])
        for (pixel in pixels) buffer.putFloat((((pixel)        and 0xFF) / 255.0f - mean[2]) / std[2])

        buffer.rewind()
        return buffer
    }
}