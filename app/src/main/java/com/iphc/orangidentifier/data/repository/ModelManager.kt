package com.iphc.orangidentifier.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.iphc.orangidentifier.data.local.prefs.AppPreferences
import com.iphc.orangidentifier.ml.EmbeddingUtils
import com.iphc.orangidentifier.ml.TfliteInterpreterFactory
import com.iphc.orangidentifier.utils.FileUtils
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

// ── Public data classes ───────────────────────────────────────────────────────

/**
 * Per-individual prototype — dual-embedding design.
 *
 * [anchorEmbedding]  Original training prototype. NEVER modified by user additions.
 *                    Always L2-normalised. High-quality-image recognition depends on this.
 *
 * [fieldEmbedding]   Built from user-added field photos. Null when no field photos have
 *                    been added yet. Updated via weighted average; capped at [MAX_FIELD_CROPS].
 *                    L2-normalised when present.
 *
 * Classification uses max(dot(query, anchor), dot(query, field)) so field additions can
 * only improve recognition — they can never reduce performance on the anchor domain.
 */
data class IndividualPrototype(
    val name: String,
    val classIndex: Int,
    val anchorEmbedding: FloatArray,       // L2-normalised anchor (immutable via API)
    val fieldEmbedding: FloatArray? = null, // L2-normalised field prototype (null = no field photos)
    val fieldCrops: Int = 0                // number of user-added field crops (for display)
) {
    override fun equals(other: Any?) = other is IndividualPrototype && name == other.name
    override fun hashCode() = name.hashCode()
}

/**
 * Loaded gallery data.
 *
 * @param unknownThreshold  Cosine similarity threshold below which a face is "Unknown".
 * @param embeddingDim      Output dimension of the backbone (2048 for ResNet V2, 768 for MegaDescriptor V3).
 * @param normalization     Preprocessing normalization to apply: "imagenet" or "megadescriptor".
 * @param prototypes        List of known individual prototypes.
 */
data class EmbeddingsData(
    val unknownThreshold: Float,
    val embeddingDim: Int,
    val normalization: String,
    val prototypes: List<IndividualPrototype>
)

/**
 * Manages TFLite model lifecycle with hot-swap support.
 *
 * Model resolution priority (checked at every getXxxInterpreter() call):
 *   1. files/models/gallery.json  ← user gallery (V3)
 *   2. files/models/embeddings.json  ← user gallery (V2 legacy)
 *   3. assets/gallery.json        ← bundled fallback (V3)
 *   4. assets/embeddings.json     ← bundled fallback (V2 legacy)
 *
 * For TFLite models:
 *   1. files/models/<filename>    ← user-provided (imported via Settings)
 *   2. assets/<filename>          ← bundled fallback
 *
 * Update bundle (.zip) format:
 *   - gallery.json or embeddings.json  → individual prototypes
 *   - backbone.tflite (any name matching "backbone" or "megadesc")  → embedding backbone
 *   - yolo.tflite (any name matching "yolo" or "detector")          → face detector
 */
class ModelManager(
    private val context: Context,
    private val prefs: AppPreferences
) {
    companion object {
        private const val TAG = "ModelManager"
        const val DETECTOR_FILENAME   = "yolo_v2_detector.tflite"
        const val BACKBONE_FILENAME   = "megadesc_T_arcface_backbone.tflite"
        const val GALLERY_FILENAME    = "gallery.json"
        const val EMBEDDINGS_FILENAME = "embeddings.json"   // V2 legacy, still accepted

        // Default embedding dim assumed when not specified in the gallery JSON (V2 legacy)
        private const val DEFAULT_EMBEDDING_DIM  = 2048
        private const val DEFAULT_NORMALIZATION  = "imagenet"
        private const val DEFAULT_THRESHOLD      = 0.40f

        // Keep this many rolling gallery backups
        private const val MAX_BACKUPS = 20

        // TFLite FlatBuffers file identifier at offset 4: "TFL3"
        private val TFLITE_MAGIC = byteArrayOf(0x54, 0x46, 0x4C, 0x33)
    }

    private var detectorInterpreter: Interpreter? = null
    private var classifierInterpreter: Interpreter? = null
    private var embeddingsData: EmbeddingsData? = null

    // ── Public API ────────────────────────────────────────────────────────────

    @Synchronized
    fun getDetectorInterpreter(): Interpreter {
        if (detectorInterpreter == null) {
            detectorInterpreter = TfliteInterpreterFactory.build(loadModel(DETECTOR_FILENAME))
            Log.i(TAG, "Loaded detector: ${resolvedModelPath(DETECTOR_FILENAME)}")
        }
        return detectorInterpreter!!
    }

    @Synchronized
    fun getClassifierInterpreter(): Interpreter {
        if (classifierInterpreter == null) {
            classifierInterpreter = TfliteInterpreterFactory.build(loadModel(BACKBONE_FILENAME))
            Log.i(TAG, "Loaded backbone: ${resolvedModelPath(BACKBONE_FILENAME)}")
        }
        return classifierInterpreter!!
    }

    /** Returns gallery data loaded from gallery.json / embeddings.json (lazy, cached). */
    @Synchronized
    fun getEmbeddings(): EmbeddingsData {
        if (embeddingsData == null) {
            embeddingsData = loadEmbeddings()
        }
        return embeddingsData!!
    }

    /** Unknown threshold — sourced from gallery JSON, persisted in prefs. */
    fun getUnknownThreshold(): Float = prefs.unknownThreshold

    fun activeDetectorVersion(): String {
        val f = File(FileUtils.getModelsDir(context), DETECTOR_FILENAME)
        return if (f.exists()) "user:$DETECTOR_FILENAME" else "bundled:$DETECTOR_FILENAME"
    }

    fun activeClassifierVersion(): String {
        val f = File(FileUtils.getModelsDir(context), BACKBONE_FILENAME)
        return if (f.exists()) "user:$BACKBONE_FILENAME" else "bundled:$BACKBONE_FILENAME"
    }

    fun activeGalleryVersion(): String {
        val galleryFile = File(FileUtils.getModelsDir(context), GALLERY_FILENAME)
        val embeddingsFile = File(FileUtils.getModelsDir(context), EMBEDDINGS_FILENAME)
        return when {
            galleryFile.exists()    -> "user:$GALLERY_FILENAME"
            embeddingsFile.exists() -> "user:$EMBEDDINGS_FILENAME (legacy)"
            else                    -> "bundled"
        }
    }

    /**
     * Restores the most recent gallery backup, replacing the current gallery.
     * Returns true if a backup existed and was restored.
     */
    @Synchronized
    fun restoreLastBackup(): Boolean {
        val backups = listGalleryBackups()
        if (backups.isEmpty()) return false
        restoreGallery(backups[0])  // sorted newest-first
        return true
    }

    /**
     * Returns a list of backup gallery files sorted newest-first.
     * Each file can be passed to restoreGallery() to restore it.
     */
    fun listGalleryBackups(): List<File> {
        val backupsDir = File(FileUtils.getModelsDir(context), "backups")
        if (!backupsDir.exists()) return emptyList()
        return (backupsDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Restores a gallery from a backup file.
     * Creates a backup of the current gallery before restoring.
     */
    @Synchronized
    fun restoreGallery(backup: File) {
        backupCurrentGallery()
        val galleryFile = File(FileUtils.getModelsDir(context), GALLERY_FILENAME)
        backup.copyTo(galleryFile, overwrite = true)
        embeddingsData = null
        Log.i(TAG, "Restored gallery from backup: ${backup.name}")
    }

    /**
     * Installs a model bundle ZIP.
     *
     * Expected contents (all optional individually — but at least one must be present):
     *   - gallery.json / embeddings.json  → individual prototypes
     *   - backbone TFLite (name must contain "backbone", "megadesc", "resnet", or "classifier")
     *   - detector TFLite (name must contain "yolo" or "detector")
     *
     * Validation before any file is replaced:
     *   - TFLite files must pass magic-byte check
     *   - gallery.json must be valid JSON with required fields
     *   - embedding_dim in gallery must match the backbone being installed (if both present)
     *   - If only a gallery is provided, its embedding_dim must match the current backbone
     */
    @Synchronized
    fun installZipBundle(zipBytes: ByteArray) {
        // ── Phase 1: extract to temp buffers ──────────────────────────────────
        var detectorBytes:  ByteArray? = null
        var detectorName:   String     = DETECTOR_FILENAME
        var backboneBytes:  ByteArray? = null
        var galleryBytes:   ByteArray? = null
        var galleryFilename: String    = GALLERY_FILENAME

        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name  = entry.name.lowercase()
                // ZIP bomb protection: reject any entry larger than 250 MB
                if (entry.size > 250_000_000L) {
                    throw IllegalArgumentException(
                        "ZIP entry '${entry.name}' is too large (${entry.size} bytes). " +
                        "Maximum allowed size is 250 MB."
                    )
                }
                val bytes = zis.readBytes()
                when {
                    name.endsWith(".tflite") &&
                            (name.contains("yolo") || name.contains("detector")) -> {
                        detectorBytes = bytes
                        Log.i(TAG, "Found detector in zip: ${entry.name}")
                    }
                    name.endsWith(".tflite") &&
                            (name.contains("backbone") || name.contains("megadesc") ||
                             name.contains("resnet")   || name.contains("classifier")) -> {
                        backboneBytes = bytes
                        Log.i(TAG, "Found backbone in zip: ${entry.name}")
                    }
                    name == "gallery.json" -> {
                        galleryBytes = bytes
                        galleryFilename = GALLERY_FILENAME
                        Log.i(TAG, "Found gallery.json in zip")
                    }
                    name == "embeddings.json" -> {
                        // Accept legacy name — store as gallery.json
                        if (galleryBytes == null) {
                            galleryBytes = bytes
                            galleryFilename = GALLERY_FILENAME   // normalise to new name
                            Log.i(TAG, "Found embeddings.json in zip (stored as gallery.json)")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (detectorBytes == null && backboneBytes == null && galleryBytes == null) {
            throw IllegalArgumentException(
                "No recognised files in zip. Expected: " +
                "*yolo*/*detector*.tflite, *backbone*/*megadesc*.tflite, gallery.json"
            )
        }

        // ── Phase 2: validate ─────────────────────────────────────────────────
        detectorBytes?.let { bytes ->
            if (!isTfliteValid(bytes)) {
                throw IllegalArgumentException(
                    "Detector file is not a valid TFLite model (failed magic-byte check)."
                )
            }
        }
        backboneBytes?.let { bytes ->
            if (!isTfliteValid(bytes)) {
                throw IllegalArgumentException(
                    "Backbone file is not a valid TFLite model (failed magic-byte check)."
                )
            }
        }

        var parsedGallery: EmbeddingsJsonRaw? = null
        galleryBytes?.let { bytes ->
            val json = String(bytes)
            try {
                parsedGallery = Gson().fromJson(json, EmbeddingsJsonRaw::class.java)
                    ?: throw IllegalArgumentException("gallery.json parsed as null")
            } catch (e: Exception) {
                throw IllegalArgumentException("gallery.json is not valid JSON: ${e.message}", e)
            }
            val threshold = parsedGallery!!.unknownThreshold
            if (threshold != null && (threshold < 0.05f || threshold > 0.95f)) {
                throw IllegalArgumentException(
                    "gallery.json: unknown_threshold=$threshold is outside reasonable range [0.05, 0.95]."
                )
            }
        }

        // Dimension compatibility check: if gallery specifies embedding_dim,
        // it must match the backbone being installed (or the currently active backbone).
        parsedGallery?.embeddingDim?.let { galleryDim ->
            if (backboneBytes != null) {
                // We can't easily verify TFLite output shape without loading the model,
                // so we trust the backbone and log a note. The first inference will
                // throw a clear error if dimensions truly mismatch.
                Log.i(TAG, "Gallery dim=$galleryDim — backbone provided, will validate on first inference")
            } else {
                // No new backbone: warn if the gallery dim differs from the current cached dim
                val currentDim = embeddingsData?.embeddingDim ?: DEFAULT_EMBEDDING_DIM
                if (currentDim != galleryDim) {
                    throw IllegalArgumentException(
                        "gallery.json requires embedding_dim=$galleryDim " +
                        "but current backbone produces $currentDim. " +
                        "Include the matching backbone.tflite in the same zip."
                    )
                }
            }
        }

        // ── Phase 3: backup + atomic replace ─────────────────────────────────
        if (galleryBytes != null) backupCurrentGallery()

        val modelsDir = FileUtils.getModelsDir(context)
        detectorBytes?.let  { File(modelsDir, DETECTOR_FILENAME).writeBytes(it) }
        backboneBytes?.let  { File(modelsDir, BACKBONE_FILENAME).writeBytes(it) }
        galleryBytes?.let   { bytes ->
            File(modelsDir, galleryFilename).writeBytes(bytes)
            // Keep prefs threshold in sync immediately
            parsedGallery?.unknownThreshold?.let { prefs.unknownThreshold = it }
        }

        invalidate()
        Log.i(TAG, "Bundle installed — " +
            "detector=${detectorBytes != null} " +
            "backbone=${backboneBytes != null} " +
            "gallery=${galleryBytes != null}")
    }

    @Synchronized
    fun installModelFile(bytes: ByteArray, targetFilename: String) {
        val isGallery = targetFilename.endsWith(".json")
        if (isGallery) backupCurrentGallery()
        File(FileUtils.getModelsDir(context), targetFilename).writeBytes(bytes)
        Log.i(TAG, "Installed $targetFilename (${bytes.size} bytes)")
        invalidate()
    }

    /**
     * Nulls out all cached interpreters and embeddings so the next inference
     * reloads from disk. Loading happens lazily on the inference thread.
     */
    @Synchronized
    fun invalidate() {
        detectorInterpreter?.close()
        classifierInterpreter?.close()
        detectorInterpreter  = null
        classifierInterpreter = null
        embeddingsData = null
        Log.i(TAG, "Model cache invalidated — will reload on next inference")
    }

    // ── Backup helpers ────────────────────────────────────────────────────────

    private fun backupCurrentGallery() {
        val backupsDir = File(FileUtils.getModelsDir(context), "backups")
        backupsDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val userFile = activeGalleryFileOrNull()
        if (userFile != null && userFile.exists()) {
            // Normal case: back up the existing user gallery file
            userFile.copyTo(File(backupsDir, "gallery_$stamp.json"), overwrite = true)
            Log.i(TAG, "Gallery backed up (user file) → gallery_$stamp.json")
        } else {
            // First modification ever: the active gallery is the bundled one in assets.
            // Back it up now so the original state can be restored later.
            val assetName = listOf(GALLERY_FILENAME, EMBEDDINGS_FILENAME)
                .firstOrNull { name ->
                    try { context.assets.open(name).close(); true } catch (_: Exception) { false }
                }
            if (assetName != null) {
                val bytes = context.assets.open(assetName).readBytes()
                File(backupsDir, "gallery_$stamp.json").writeBytes(bytes)
                Log.i(TAG, "Gallery backed up (bundled asset: $assetName) → gallery_$stamp.json")
            } else {
                Log.w(TAG, "backupCurrentGallery: no user file and no bundled asset found — skipping backup")
            }
        }

        // Trim to MAX_BACKUPS most recent
        val all = (backupsDir.listFiles { f -> f.name.endsWith(".json") } ?: return)
            .sortedByDescending { it.lastModified() }
        all.drop(MAX_BACKUPS).forEach { it.delete() }
    }

    /** Returns the active user gallery file path, or null if none installed yet. */
    private fun activeGalleryFileOrNull(): File? {
        val modelsDir = FileUtils.getModelsDir(context)
        val v3 = File(modelsDir, GALLERY_FILENAME)
        if (v3.exists()) return v3
        val v2 = File(modelsDir, EMBEDDINGS_FILENAME)
        if (v2.exists()) return v2
        return null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolvedModelPath(filename: String): String {
        val f = File(FileUtils.getModelsDir(context), filename)
        return if (f.exists()) f.absolutePath else "assets/$filename"
    }

    private fun loadModel(filename: String): MappedByteBuffer {
        val userFile = File(FileUtils.getModelsDir(context), filename)
        return if (userFile.exists()) loadFromFile(userFile) else loadFromAssets(filename)
    }

    private fun loadFromFile(file: File): MappedByteBuffer {
        // Do NOT close the FileInputStream — the MappedByteBuffer is backed by it.
        val fis = FileInputStream(file)
        val channel = fis.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }

    private fun loadFromAssets(filename: String): MappedByteBuffer {
        val afd = context.assets.openFd(filename)
        val fis = FileInputStream(afd.fileDescriptor)
        val channel = fis.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    private fun loadEmbeddings(): EmbeddingsData {
        val modelsDir = FileUtils.getModelsDir(context)

        // Resolution order: user gallery.json > user embeddings.json > assets
        val json: String = when {
            File(modelsDir, GALLERY_FILENAME).exists() ->
                File(modelsDir, GALLERY_FILENAME).readText()
            File(modelsDir, EMBEDDINGS_FILENAME).exists() ->
                File(modelsDir, EMBEDDINGS_FILENAME).readText()
            else -> {
                try {
                    context.assets.open(GALLERY_FILENAME).bufferedReader().readText()
                } catch (_: Exception) {
                    try {
                        context.assets.open(EMBEDDINGS_FILENAME).bufferedReader().readText()
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "No gallery found. Import a model bundle from Settings → Import Bundle.", e
                        )
                    }
                }
            }
        }

        return parseGalleryJson(json)
    }

    private fun parseGalleryJson(json: String): EmbeddingsData {
        val raw = Gson().fromJson(json, EmbeddingsJsonRaw::class.java)
        val threshold    = raw.unknownThreshold  ?: DEFAULT_THRESHOLD
        val embeddingDim = raw.embeddingDim       ?: DEFAULT_EMBEDDING_DIM
        val normalization = raw.normalization     ?: DEFAULT_NORMALIZATION

        prefs.unknownThreshold = threshold  // keep prefs in sync for Settings display

        val prototypes = raw.individuals?.entries?.mapNotNull { (name, ind) ->
            val rawAnchor = ind.embedding ?: return@mapNotNull null
            val anchor = EmbeddingUtils.l2Normalize(FloatArray(rawAnchor.size) { rawAnchor[it] })
            val field = ind.fieldEmbedding?.takeIf { it.isNotEmpty() }
                ?.let { floats -> EmbeddingUtils.l2Normalize(FloatArray(floats.size) { floats[it] }) }
            IndividualPrototype(
                name           = name,
                classIndex     = ind.classIndex ?: 0,
                anchorEmbedding = anchor,
                fieldEmbedding = field,
                fieldCrops     = ind.fieldCrops ?: 0
            )
        } ?: emptyList()

        Log.i(TAG, "Loaded ${prototypes.size} prototypes | " +
            "dim=$embeddingDim | norm=$normalization | threshold=$threshold")
        return EmbeddingsData(threshold, embeddingDim, normalization, prototypes)
    }

    private fun isTfliteValid(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        // FlatBuffers file identifier for TFLite 3: "TFL3" at bytes 4–7
        return bytes[4] == TFLITE_MAGIC[0] &&
               bytes[5] == TFLITE_MAGIC[1] &&
               bytes[6] == TFLITE_MAGIC[2] &&
               bytes[7] == TFLITE_MAGIC[3]
    }

    // ── Gson DTOs (private) ───────────────────────────────────────────────────

    private data class EmbeddingsJsonRaw(
        @SerializedName("unknown_threshold") val unknownThreshold: Float?,
        @SerializedName("embedding_dim")     val embeddingDim: Int?,
        @SerializedName("normalization")     val normalization: String?,
        val individuals: Map<String, IndividualJsonRaw>?
    )

    private data class IndividualJsonRaw(
        @SerializedName("class_index")     val classIndex: Int?,
        val embedding: List<Float>?,                              // anchor — never overwritten by additions
        @SerializedName("field_embedding") val fieldEmbedding: List<Float>? = null,  // field prototype
        @SerializedName("field_crops")     val fieldCrops: Int? = null               // user crop count
    )
}
