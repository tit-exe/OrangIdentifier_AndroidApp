package com.iphc.orangidentifier.ui.add_individual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iphc.orangidentifier.data.repository.GalleryManager
import com.iphc.orangidentifier.ml.EmbeddingUtils
import com.iphc.orangidentifier.ml.classifier.ImagePreprocessor
import com.iphc.orangidentifier.ml.classifier.MegaDescriptorBackbone
import com.iphc.orangidentifier.ml.detector.YoloDetector
import com.iphc.orangidentifier.utils.BitmapUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * One detected and saved face crop.
 *
 * @param cropPath     Saved JPEG of the cropped face (cacheDir/individual_crops/).
 * @param sourceUri    Original image URI — valid for the duration of the app session.
 *                     Used to re-decode the image when the user edits the crop box.
 * @param boundingBox  YOLO bounding box in ORIGINAL image pixel coordinates.
 */
data class CropItem(
    val cropPath: String,
    val sourceUri: Uri,
    val boundingBox: RectF
)

/**
 * Shared ViewModel for the Add Individual flow.
 * Survives navigation between AddIndividualFragment, CropReviewFragment, CropEditorFragment.
 *
 * Flow:
 *   1. User enters name, selects 1–N photos (camera or multi-pick gallery)
 *   2. Each photo → YOLO → CropItem list
 *   3. CropReviewFragment: user can delete or re-crop individual items
 *   4. Confirm: backbone.embed() per crop → average prototype → GalleryManager.addIndividual()
 */
@HiltViewModel
class AddIndividualViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val yoloDetector: YoloDetector,
    private val backbone: MegaDescriptorBackbone,
    private val galleryManager: GalleryManager
) : ViewModel() {

    companion object {
        private const val TAG       = "AddIndividualVM"
        const val MIN_CROPS_HARD    = 5
        const val MIN_CROPS_WARN    = 10
    }

    // ── Observable state ──────────────────────────────────────────────────────

    private val _name       = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    /** Non-empty when the flow is locked to an existing individual (launched from Gallery). */
    private val _lockedName = MutableStateFlow("")
    val lockedName: StateFlow<String> = _lockedName.asStateFlow()

    private val _cropItems  = MutableStateFlow<List<CropItem>>(emptyList())
    val cropItems: StateFlow<List<CropItem>> = _cropItems.asStateFlow()

    /**
     * Mutex that serialises all writes to [_cropItems].
     * Prevents lost-update race between [processPhotos] (append) and [updateCrop] (modify-at-index).
     */
    private val cropMutex = Mutex()

    /**
     * Holds the active [processPhotos] coroutine so [confirmAndAdd] and [updateCrop]
     * can cancel it and take priority over the YOLO processing queue.
     */
    private var processPhotosJob: Job? = null

    sealed class UiState {
        object Idle                          : UiState()
        data class ProcessingPhoto(
            val processed: Int = 0,
            val total: Int = 0
        )                                    : UiState()
        object ComputingEmbeddings           : UiState()
        data class Success(val name: String) : UiState()
        data class Error(val message: String): UiState()
        data class SimilarityWarning(
            val similarName: String,
            val similarity: Float,
            val pendingEmbeddings: List<FloatArray>
        )                                    : UiState()
        /**
         * All submitted photos failed the quality gate — they are too different from
         * the individual's anchor to be useful as field data.
         * Shown as a dialog (not a toast) so the user reads the explanation.
         */
        data class QualityGateRejection(
            val individualName: String
        )                                    : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setName(n: String) { _name.value = n.trim() }

    /** Called by GalleryFragment → AddIndividual: clears state, locks name, prevents editing. */
    fun lockToExisting(name: String) {
        clearAll()
        _lockedName.value = name
        _name.value = name
    }

    /** Called when entering AddIndividual from Home (normal mode). */
    fun clearLock() { _lockedName.value = "" }

    fun removeCrop(item: CropItem) {
        _cropItems.value = _cropItems.value.filter { it != item }
        File(item.cropPath).delete()
    }

    fun resetState() { _uiState.value = UiState.Idle }

    // ── Photo processing ──────────────────────────────────────────────────────

    /** Single photo — convenience wrapper. */
    fun processPhoto(uri: Uri) { processPhotos(listOf(uri)) }

    /**
     * Processes a list of URIs sequentially (TFLite is NOT thread-safe).
     * Stores the [Job] so that [confirmAndAdd] and [updateCrop] can cancel it and
     * take priority over the YOLO processing queue.
     */
    fun processPhotos(uris: List<Uri>) {
        processPhotosJob?.cancel()   // cancel any previous run first
        if (uris.isEmpty()) return
        processPhotosJob = viewModelScope.launch {
            _uiState.value = UiState.ProcessingPhoto(processed = 0, total = uris.size)
            try {
                for ((idx, uri) in uris.withIndex()) {
                    ensureActive()   // respect cancellation between each image
                    val newItems = withContext(Dispatchers.Default) {
                        val bitmap = BitmapUtils.decodeUri(context, uri)
                            ?: return@withContext emptyList()
                        try {
                            detectAndSaveCrops(bitmap, uri)
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                    // Atomic append under mutex to prevent lost-update with updateCrop
                    cropMutex.withLock {
                        _cropItems.value = _cropItems.value + newItems
                    }
                    Log.i(TAG, "Photo ${idx + 1}/${uris.size} → ${newItems.size} crops (total ${_cropItems.value.size})")
                    _uiState.value = UiState.ProcessingPhoto(processed = idx + 1, total = uris.size)
                }
                _uiState.value = UiState.Idle
            } catch (e: CancellationException) {
                // Cancelled by confirmAndAdd or updateCrop — reset state silently
                _uiState.value = UiState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "processPhotos error", e)
                _uiState.value = UiState.Error(e.message ?: "Processing failed")
            }
        }
    }

    /**
     * Replaces the crop at [cropIndex] with the user-adjusted [newBox].
     *
     * Takes priority over ongoing [processPhotos]: suspends the YOLO queue by
     * acquiring [cropMutex] for the final write, ensuring the edit is never lost.
     * Does NOT cancel processPhotos — it resumes naturally after the edit completes.
     */
    suspend fun updateCrop(cropIndex: Int, newBox: RectF) {
        // Read the item under lock so we get a consistent snapshot
        val item = cropMutex.withLock { _cropItems.value.getOrNull(cropIndex) } ?: return

        // Do the heavy IO work WITHOUT holding the lock (no need to block processPhotos appends)
        var newFilePath: String? = null
        withContext(Dispatchers.Default) {
            val bitmap = BitmapUtils.decodeUri(context, item.sourceUri) ?: return@withContext
            try {
                val crop    = ImagePreprocessor.cropFace(bitmap, newBox)
                val dir     = File(item.cropPath).parentFile ?: return@withContext
                val newFile = File(dir, "crop_${System.currentTimeMillis()}_ed.jpg")
                FileOutputStream(newFile).use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                File(item.cropPath).delete()
                crop.recycle()
                newFilePath = newFile.absolutePath
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        // Atomic write under lock: read current list (may include new items from processPhotos),
        // replace only the target index, then write back.
        newFilePath?.let { path ->
            cropMutex.withLock {
                val current = _cropItems.value.toMutableList()
                if (cropIndex < current.size) {
                    current[cropIndex] = item.copy(cropPath = path, boundingBox = newBox)
                    _cropItems.value = current
                }
            }
        }
    }

    // ── Confirm & add ─────────────────────────────────────────────────────────

    fun confirmAndAdd() {
        // Cancel any ongoing YOLO processing — confirmAndAdd takes priority.
        // Crops already processed and saved are still in _cropItems.
        processPhotosJob?.cancel()
        processPhotosJob = null

        val individualName = _name.value
        if (individualName.isBlank()) {
            _uiState.value = UiState.Error("Please enter a name first")
            return
        }
        val paths = _cropItems.value.map { it.cropPath }
        if (paths.size < MIN_CROPS_HARD) {
            _uiState.value = UiState.Error("Not enough crops (${paths.size}/$MIN_CROPS_HARD minimum)")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.ComputingEmbeddings
            try {
                val embeddings = withContext(Dispatchers.Default) {
                    paths.mapNotNull { path ->
                        try {
                            val bmp = BitmapFactory.decodeFile(path)
                                ?: return@mapNotNull null
                            backbone.embed(bmp)  // recycles bmp internally
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to embed crop: $path", e)
                            null
                        }
                    }
                }

                if (embeddings.isEmpty()) {
                    _uiState.value = UiState.Error("All crops failed — try again with clearer photos")
                    return@launch
                }

                val proto   = EmbeddingUtils.averageEmbeddings(embeddings)
                val similar = withContext(Dispatchers.IO) {
                    galleryManager.findSimilarIndividuals(proto, threshold = 0.82f)
                        .filter { it.first != individualName }
                }
                if (similar.isNotEmpty()) {
                    val (simName, simScore) = similar.first()
                    _uiState.value = UiState.SimilarityWarning(simName, simScore, embeddings)
                    return@launch
                }

                finishAdd(individualName, embeddings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "confirmAndAdd error", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun confirmAddDespiteWarning(embeddings: List<FloatArray>) {
        viewModelScope.launch { finishAdd(_name.value, embeddings) }
    }

    private suspend fun finishAdd(individualName: String, embeddings: List<FloatArray>) {
        if (galleryManager.individualExists(individualName)) {
            // Existing individual: field crops only — anchor is NEVER modified.
            // The quality gate rejects images whose embeddings are too far from the anchor,
            // because they would corrupt the prototype and cause false positives.
            val result = withContext(Dispatchers.IO) {
                galleryManager.addFieldCrops(individualName, embeddings)
            }
            _uiState.value = when {
                result.accepted > 0 -> UiState.Success(individualName)
                result.rejected > 0 -> UiState.QualityGateRejection(individualName)
                result.capped > 0   -> UiState.Error("$individualName's field profile is full (${GalleryManager.MAX_FIELD_CROPS} photos max).")
                else                -> UiState.Error("Could not write to gallery")
            }
        } else {
            // New individual: compute prototype from scratch
            val added = withContext(Dispatchers.IO) { galleryManager.addIndividual(individualName, embeddings) }
            _uiState.value = if (added) UiState.Success(individualName)
                             else UiState.Error("Could not write to gallery")
        }
    }

    // ── Gallery queries ───────────────────────────────────────────────────────

    fun individualExists(name: String) = galleryManager.individualExists(name)

    fun exportPatch(individualName: String): File? =
        galleryManager.exportPatch(listOf(individualName))

    fun createShareIntent(patchFile: File) =
        galleryManager.createShareIntent(patchFile)

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        clearTempCrops()
    }

    fun clearAll() {
        clearTempCrops()
        _cropItems.value = emptyList()
        _name.value = ""
        _uiState.value = UiState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun detectAndSaveCrops(bitmap: Bitmap, sourceUri: Uri): List<CropItem> {
        val rawDetections = yoloDetector.detect(bitmap)
        if (rawDetections.isEmpty()) return emptyList()

        val outDir = File(context.cacheDir, "individual_crops")
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "Cannot create crops directory: ${outDir.absolutePath}")
            return emptyList()
        }

        val savedItems = mutableListOf<CropItem>()
        for ((idx, raw) in rawDetections.withIndex()) {
            var crop: android.graphics.Bitmap? = null
            try {
                crop = ImagePreprocessor.cropFace(bitmap, raw.box)
                val file = File(outDir, "crop_${System.currentTimeMillis()}_$idx.jpg")
                FileOutputStream(file).use { fos ->
                    crop.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                savedItems.add(CropItem(
                    cropPath    = file.absolutePath,
                    sourceUri   = sourceUri,
                    boundingBox = RectF(raw.box)
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save crop $idx", e)
            } finally {
                crop?.recycle()
            }
        }
        return savedItems
    }

    private fun clearTempCrops() {
        File(context.cacheDir, "individual_crops").listFiles()?.forEach { it.delete() }
    }
}
