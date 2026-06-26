package com.iphc.orangidentifier.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.iphc.orangidentifier.data.local.prefs.AppPreferences
import com.iphc.orangidentifier.ml.EmbeddingUtils
import com.iphc.orangidentifier.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles gallery JSON read/write operations at the app level:
 *   - Adding a new individual (computed prototype → gallery.json)
 *   - Exporting a patch file (new individuals → shareable JSON)
 *   - Importing a patch from another device
 *
 * Always delegates physical file writes to [ModelManager.installModelFile] so that
 * automatic backup, cache invalidation, and atomic write are handled in one place.
 */
@Singleton
class GalleryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val prefs: AppPreferences
) {
    companion object {
        private const val TAG = "GalleryManager"

        /**
         * Maximum number of user-added field crops kept in [field_embedding].
         * After this cap, new crops are silently discarded to prevent prototype drift.
         */
        const val MAX_FIELD_CROPS = 50

        /**
         * Gate ratio for a SINGLE submitted photo (strict).
         * A screenshot or doubly-compressed image typically lands at ~0–3% similarity.
         * A genuine field photo of the right individual typically lands at 15–25%.
         * At 65% of threshold (≈ 0.143 with default threshold=0.22), we cleanly
         * separate usable field photos from noise, without rejecting good camera shots.
         */
        private const val SINGLE_GATE_RATIO = 0.65f

        /**
         * Gate ratio checked against the AVERAGE of a batch (more lenient).
         * Averaging many noisy-but-correct photos cancels random noise and brings
         * the centroid closer to the individual's true embedding. Even if each photo
         * individually scores below [SINGLE_GATE_RATIO], their average can pass here.
         */
        private const val BATCH_GATE_RATIO = 0.45f

        /** Minimum number of photos to use the batch-average gate instead of per-photo gate. */
        private const val BATCH_MIN_SIZE = 3
    }

    // ── Add individual ────────────────────────────────────────────────────────

    /**
     * Computes the prototype embedding from [embeddings], adds the individual to
     * gallery.json, and calls modelManager.installModelFile (which backs up the
     * current gallery + invalidates the cache).
     *
     * @param name       Display name for the individual.
     * @param embeddings List of L2-normalised 768/2048-dim vectors, one per crop.
     * @return true on success, false if the gallery file could not be resolved.
     */
    fun addIndividual(name: String, embeddings: List<FloatArray>): Boolean {
        if (embeddings.isEmpty()) return false

        val prototype = EmbeddingUtils.averageEmbeddings(embeddings)

        // Read current gallery JSON (preserves all metadata)
        val galleryJson = readCurrentGalleryJson()

        // Get or create the "individuals" object
        val individuals = galleryJson.getAsJsonObject("individuals")
            ?: JsonObject().also { galleryJson.add("individuals", it) }

        // Determine next class_index
        val nextIndex = if (individuals.size() == 0) 0
        else individuals.entrySet().mapNotNull { e ->
            e.value.asJsonObject.get("class_index")?.asInt
        }.maxOrNull()?.plus(1) ?: individuals.size()

        // Build individual entry
        val entry = JsonObject().apply {
            addProperty("class_index", nextIndex)
            addProperty("num_training_crops", embeddings.size)
            addProperty("added_at", isoNow())
            add("embedding", JsonArray().also { arr ->
                prototype.forEach { v -> arr.add(v) }
            })
            // V6 compatibility: wrap the centroid as a single exemplar so max-over-exemplars
            // scoring works for user-added individuals. Using the centroid (average of N crops)
            // rather than individual crop embeddings avoids generic/noisy single-crop vectors.
            add("exemplars", JsonArray().also { outer ->
                outer.add(JsonArray().also { inner -> prototype.forEach { v -> inner.add(v) } })
            })
            addProperty("num_exemplars", 1)
        }
        individuals.add(name, entry)
        galleryJson.add("individuals", individuals)

        // Write via ModelManager (handles backup + invalidate)
        val bytes = galleryJson.toString().toByteArray(Charsets.UTF_8)
        modelManager.installModelFile(bytes, ModelManager.GALLERY_FILENAME)

        Log.i(TAG, "Added '$name' — ${embeddings.size} crops, class_index=$nextIndex")
        return true
    }

    /**
     * Returns individuals whose prototype similarity to [prototype] exceeds [threshold].
     * Used to detect potential duplicates before adding a new individual.
     */
    fun findSimilarIndividuals(prototype: FloatArray, threshold: Float = 0.82f): List<Pair<String, Float>> {
        return try {
            modelManager.getEmbeddings().prototypes
                .map { proto ->
                    // V6: max-over-exemplars mirrors the exact score inference would produce.
                    // V3 fallback: exemplars = [anchor], so maxOf degrades to a single dot product.
                    val maxSim = proto.exemplars.maxOf { EmbeddingUtils.dotProduct(prototype, it) }
                    proto.name to maxSim
                }
                .filter { it.second >= threshold }
                .sortedByDescending { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class AddFieldResult(
        val accepted: Int,  // embeddings merged into field_embedding
        val rejected: Int,  // embeddings below quality gate (too different from anchor)
        val capped: Int     // embeddings discarded because MAX_FIELD_CROPS was reached
    ) {
        val ok get() = accepted > 0
    }

    /**
     * Adds [newEmbeddings] to the [field_embedding] of [name].
     *
     * IMPORTANT: the anchor [embedding] is NEVER touched. Field additions are stored
     * separately so they can only improve recognition (max strategy in the backbone).
     *
     * Quality gate: embeddings whose cosine similarity with the anchor is below
     * [unknownThreshold × 0.5] are rejected — they are likely from a wrong individual,
     * a screenshot, or an extremely blurry photo.
     *
     * Cap: after [MAX_FIELD_CROPS] total field crops the field_embedding is considered
     * stable; additional crops are discarded to prevent drift.
     */
    fun addFieldCrops(name: String, newEmbeddings: List<FloatArray>): AddFieldResult {
        if (newEmbeddings.isEmpty()) return AddFieldResult(0, 0, 0)

        val galleryJson = readCurrentGalleryJson()
        val individuals = galleryJson.getAsJsonObject("individuals") ?: return AddFieldResult(0, 0, 0)
        val entry = individuals.getAsJsonObject(name) ?: run {
            Log.w(TAG, "addFieldCrops: '$name' not found"); return AddFieldResult(0, 0, 0)
        }

        val anchorArr = entry.getAsJsonArray("embedding") ?: return AddFieldResult(0, 0, 0)
        val anchor    = FloatArray(anchorArr.size()) { anchorArr[it].asFloat }

        // V6: compare field crops against exemplars (max-over-exemplars), not just centroid.
        // V3 fallback: exemplars array absent → selfVectors = [anchor] (same as before).
        val exemplarsArr = entry.getAsJsonArray("exemplars")
        val selfVectors: List<FloatArray> = if (exemplarsArr != null && exemplarsArr.size() > 0) {
            (0 until exemplarsArr.size()).map { i ->
                val ex = exemplarsArr[i].asJsonArray
                FloatArray(ex.size()) { j -> ex[j].asFloat }
            }
        } else {
            listOf(anchor)
        }

        // ── Quality gate ──────────────────────────────────────────────────────
        // Two modes depending on batch size:
        //
        // SINGLE photo (< BATCH_MIN_SIZE): strict per-photo check.
        //   Compares each embedding against the BEST matching training exemplar.
        //   Gate = threshold × SINGLE_GATE_RATIO.
        //
        // BATCH (≥ BATCH_MIN_SIZE): check the AVERAGE of the whole batch.
        //   Averaging cancels noise — the centroid of good photos passes even if
        //   individual crops are slightly below the single gate.
        //   Gate = threshold × BATCH_GATE_RATIO (more lenient).
        var rejected = 0
        val validEmbs: List<FloatArray>
        if (newEmbeddings.size >= BATCH_MIN_SIZE) {
            val batchAvg  = EmbeddingUtils.averageEmbeddings(newEmbeddings)
            val batchSim  = selfVectors.maxOf { EmbeddingUtils.dotProduct(batchAvg, it) }
            val batchGate = prefs.unknownThreshold * BATCH_GATE_RATIO
            if (batchSim >= batchGate) {
                validEmbs = newEmbeddings       // whole batch passes as a group
            } else {
                rejected  = newEmbeddings.size  // whole batch fails as a group
                validEmbs = emptyList()
            }
            Log.d(TAG, "addFieldCrops '$name': batch avg sim=${"%.3f".format(batchSim)} gate=${"%.3f".format(batchGate)}")
        } else {
            val singleGate = prefs.unknownThreshold * SINGLE_GATE_RATIO
            validEmbs = newEmbeddings.filter { emb ->
                val sim = selfVectors.maxOf { EmbeddingUtils.dotProduct(emb, it) }
                if (sim < singleGate) { rejected++; false } else true
            }
        }

        // ── Cap check ─────────────────────────────────────────────────────────
        val currentFieldCrops = entry.get("field_crops")?.asInt ?: 0
        val remaining = (MAX_FIELD_CROPS - currentFieldCrops).coerceAtLeast(0)
        val toAdd  = minOf(validEmbs.size, remaining)
        val capped = validEmbs.size - toAdd

        if (toAdd == 0) {
            Log.i(TAG, "addFieldCrops '$name': all $rejected rejected, $capped capped")
            return AddFieldResult(0, rejected, capped)
        }

        val embsToAdd = validEmbs.take(toAdd)
        val dim       = anchor.size
        val newTotal  = currentFieldCrops + toAdd

        // ── Weighted-average merge ────────────────────────────────────────────
        val existingFieldArr = entry.getAsJsonArray("field_embedding")
        val currentField = existingFieldArr
            ?.takeIf { it.size() == dim }
            ?.let { FloatArray(dim) { i -> it[i].asFloat } }

        val merged = FloatArray(dim)
        if (currentField != null && currentFieldCrops > 0) {
            // Blend existing field prototype with new embeddings
            for (i in 0 until dim) {
                merged[i] = currentField[i] * currentFieldCrops
                for (emb in embsToAdd) { if (i < emb.size) merged[i] += emb[i] }
                merged[i] /= newTotal
            }
        } else {
            // No existing field — average all new embeddings
            for (i in 0 until dim) {
                for (emb in embsToAdd) { if (i < emb.size) merged[i] += emb[i] }
                merged[i] /= toAdd
            }
        }
        val normed = EmbeddingUtils.l2Normalize(merged)

        // ── Post-merge validation ─────────────────────────────────────────────
        // The merged prototype must pass two checks before being written to disk.
        // Both use the same threshold as live classification for consistency.
        //
        // Check 1 — self-similarity: the merged prototype must resemble [name].
        //   Uses max-over-exemplars (same metric as inference) — if even the closest
        //   training exemplar doesn't recognise this field prototype, it's too generic.
        //
        // Check 2 — no false positives: the merged prototype must not exceed threshold
        //   against ANY exemplar of any other individual. Uses all exemplars, not just
        //   centroids, to mirror exactly what inference would see.
        val selfSim = selfVectors.maxOf { EmbeddingUtils.dotProduct(normed, it) }
        if (selfSim < prefs.unknownThreshold) {
            Log.w(TAG, "addFieldCrops '$name': merged prototype selfSim=${"%.3f".format(selfSim)} " +
                "< threshold=${prefs.unknownThreshold} — too generic, discarded")
            return AddFieldResult(0, toAdd, 0)
        }
        // Collect ALL exemplar vectors from other individuals (V6: up to 25 each; V3: [anchor])
        val otherExemplars: List<FloatArray> = individuals.entrySet()
            .filter { (k, _) -> k != name }
            .flatMap { (_, v) ->
                val obj   = v.asJsonObject
                val exArr = obj.getAsJsonArray("exemplars")
                if (exArr != null && exArr.size() > 0) {
                    (0 until exArr.size()).map { i ->
                        val ex = exArr[i].asJsonArray
                        FloatArray(ex.size()) { j -> ex[j].asFloat }
                    }
                } else {
                    obj.getAsJsonArray("embedding")
                        ?.let { arr -> listOf(FloatArray(arr.size()) { i -> arr[i].asFloat }) }
                        ?: emptyList()
                }
            }
        if (otherExemplars.isNotEmpty()) {
            val maxOtherSim = otherExemplars.maxOf { EmbeddingUtils.dotProduct(normed, it) }
            if (maxOtherSim >= prefs.unknownThreshold) {
                Log.w(TAG, "addFieldCrops '$name': merged prototype would match another individual " +
                    "(max_other=${"%.3f".format(maxOtherSim)} ≥ threshold) — discarded to prevent false positives")
                return AddFieldResult(0, toAdd, 0)
            }
        }

        val fieldArr = JsonArray().also { normed.forEach { v -> it.add(v) } }
        entry.add("field_embedding", fieldArr)
        entry.addProperty("field_crops", newTotal)
        entry.addProperty("last_field_update", isoNow())
        individuals.add(name, entry)
        galleryJson.add("individuals", individuals)

        val bytes = galleryJson.toString().toByteArray(Charsets.UTF_8)
        modelManager.installModelFile(bytes, ModelManager.GALLERY_FILENAME)
        Log.i(TAG, "addFieldCrops '$name': accepted=$toAdd rejected=$rejected capped=$capped (total field: $newTotal)")
        return AddFieldResult(toAdd, rejected, capped)
    }

    /** Returns true if [name] already exists in the gallery. */
    fun individualExists(name: String): Boolean {
        val gallery = readCurrentGalleryJson()
        return gallery.getAsJsonObject("individuals")?.has(name) == true
    }

    // ── Patch export ──────────────────────────────────────────────────────────

    /**
     * Creates a patch JSON file containing [individualNames] (must already be in
     * the gallery) and returns a content URI that can be shared via Intent.ACTION_SEND.
     *
     * The patch format is:
     * {
     *   "patch_version": "1.0",
     *   "device_name": "<ranger name>",
     *   "created_at": "<ISO timestamp>",
     *   "added_individuals": {
     *     "<name>": { "embedding": [...], "num_crops": N, "added_at": "..." }
     *   }
     * }
     *
     * @param individualNames Names to include in the patch.
     * @return The patch File, or null if the gallery could not be read.
     */
    fun exportPatch(individualNames: List<String>): File? {
        val gallery = readCurrentGalleryJson()
        val individuals = gallery.getAsJsonObject("individuals") ?: return null

        val addedIndividuals = JsonObject()
        for (name in individualNames) {
            val entry = individuals.getAsJsonObject(name) ?: continue
            val patchEntry = JsonObject().apply {
                // Keep only what the receiver needs
                add("embedding",    entry.get("embedding"))
                add("num_crops",    entry.get("num_training_crops") ?: JsonPrimitive(0))
                add("added_at",     entry.get("added_at") ?: JsonPrimitive(isoNow()))
                // V6: include exemplars so receiver can do max-over-exemplars without fallback
                entry.getAsJsonArray("exemplars")?.let { add("exemplars", it) }
            }
            addedIndividuals.add(name, patchEntry)
        }

        if (addedIndividuals.size() == 0) return null

        val patch = JsonObject().apply {
            addProperty("patch_version",      "1.0")
            addProperty("device_name",        prefs.rangerName.ifBlank { "Unknown device" })
            addProperty("created_at",         isoNow())
            addProperty("embedding_dim",      gallery.get("embedding_dim")?.asInt ?: 768)
            add("added_individuals", addedIndividuals)
        }

        // Write to exports directory (accessible via FileProvider)
        val exportsDir = File(context.filesDir, "exports")
        exportsDir.mkdirs()
        val stamp   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ranger  = prefs.rangerName.replace(" ", "_").ifBlank { "device" }
        val file    = File(exportsDir, "patch_${ranger}_$stamp.json")
        file.writeText(patch.toString())

        Log.i(TAG, "Patch exported: ${file.name} (${addedIndividuals.size()} individuals)")
        return file
    }

    /**
     * Exports the complete active gallery.json to the exports directory and returns the file.
     * The receiver imports it via Settings → "Import Labels" to fully replace their gallery.
     * Unlike exportPatch(), this includes ALL individuals (bundled + user-added) and the full
     * exemplar sets, making it suitable for migrating to a new device or sharing with a colleague.
     */
    fun exportFullGallery(): File? {
        return try {
            val galleryJson = readCurrentGalleryJson()
            val exportsDir = File(context.filesDir, "exports").also { it.mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(exportsDir, "gallery_export_$stamp.json")
            file.writeText(galleryJson.toString())
            Log.i(TAG, "Full gallery exported: ${file.name} (${file.length()/1024} KB)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "exportFullGallery failed", e)
            null
        }
    }

    /**
     * Creates a sharing Intent for [patchFile] via FileProvider.
     * The caller is responsible for starting the activity.
     */
    fun createShareIntent(patchFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            patchFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type      = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "OrangIdentifier patch — ${patchFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Gallery info ──────────────────────────────────────────────────────────

    data class IndividualSummary(
        val name: String,
        val numCrops: Int?,      // null = no metadata (default gallery individuals)
        val fieldCrops: Int = 0, // user-added field crops (0 = none added yet)
        val addedAt: String?     // null = no metadata
    )

    /** Full list with optional metadata. Unknown fields are null (safe for default gallery). */
    fun getIndividualsSummary(): List<IndividualSummary> {
        return try {
            val individuals = readCurrentGalleryJson().getAsJsonObject("individuals")
                ?: return emptyList()
            individuals.entrySet().map { (name, elem) ->
                val obj = elem.asJsonObject
                IndividualSummary(
                    name       = name,
                    numCrops   = obj.get("num_training_crops")?.takeIf { !it.isJsonNull }?.asInt,
                    fieldCrops = obj.get("field_crops")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    addedAt    = obj.get("added_at")?.takeIf { !it.isJsonNull }?.asString
                )
            }.sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Returns the list of individual names currently in the gallery. */
    fun listIndividuals(): List<String> {
        return try {
            readCurrentGalleryJson().getAsJsonObject("individuals")
                ?.entrySet()?.map { it.key } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Removes [name] from the gallery and persists (with automatic backup).
     * @return true if the individual existed and was removed.
     */
    fun removeIndividual(name: String): Boolean {
        val galleryJson = readCurrentGalleryJson()
        val individuals = galleryJson.getAsJsonObject("individuals") ?: return false
        if (!individuals.has(name)) return false
        individuals.remove(name)
        galleryJson.add("individuals", individuals)
        val bytes = galleryJson.toString().toByteArray(Charsets.UTF_8)
        modelManager.installModelFile(bytes, ModelManager.GALLERY_FILENAME)
        Log.i(TAG, "Removed individual '$name'")
        return true
    }

    /** Returns true if at least one automatic gallery backup exists. */
    fun hasBackups(): Boolean = modelManager.listGalleryBackups().isNotEmpty()

    /**
     * Reverts the gallery to its most recent automatic backup.
     * Useful for undoing an accidental "Add to gallery" action.
     * Returns true if a backup was found and restored.
     */
    fun undoLastGalleryChange(): Boolean = modelManager.restoreLastBackup()

    data class ImportResult(val newCount: Int, val updatedCount: Int, val skippedCount: Int)

    /**
     * Merges a patch JSON (as raw bytes) into the current gallery.
     * - New individuals are added directly.
     * - Existing individuals are merged via weighted average (uses num_crops for weight).
     * - Invalid/incompatible entries are skipped.
     *
     * Always backs up the current gallery before writing.
     */
    fun importPatch(patchBytes: ByteArray): ImportResult {
        val patchJson = try {
            JsonParser.parseString(String(patchBytes, Charsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            Log.e(TAG, "importPatch: invalid JSON", e)
            return ImportResult(0, 0, 0)
        }

        val patchIndividuals = patchJson.getAsJsonObject("added_individuals")
            ?: return ImportResult(0, 0, 0)

        val galleryJson = readCurrentGalleryJson()
        val currentIndividuals = galleryJson.getAsJsonObject("individuals")
            ?: JsonObject().also { galleryJson.add("individuals", it) }

        val expectedDim = galleryJson.get("embedding_dim")?.asInt ?: 768
        var newCount     = 0
        var updatedCount = 0
        var skippedCount = 0

        for ((name, patchElem) in patchIndividuals.entrySet()) {
            // Validate individual name to prevent injection or UI overflow
            if (!isValidIndividualName(name)) {
                Log.w(TAG, "importPatch: rejected invalid name '${name.take(32)}'")
                skippedCount++
                continue
            }
            val patchEntry  = patchElem.asJsonObject
            val patchEmbArr = patchEntry.getAsJsonArray("embedding")
            if (patchEmbArr == null || patchEmbArr.size() != expectedDim) {
                skippedCount++
                continue
            }

            val patchProto  = FloatArray(patchEmbArr.size()) { patchEmbArr[it].asFloat }
            val patchCrops  = patchEntry.get("num_crops")?.asInt ?: 1

            if (!currentIndividuals.has(name)) {
                // New individual — copy directly
                currentIndividuals.add(name, patchEntry.deepCopy().apply {
                    addProperty("num_training_crops", patchCrops)
                    remove("num_crops")
                    // V6: ensure exemplars exist for max-over-exemplars scoring.
                    // New exportPatch() includes them; older patches may not — wrap centroid.
                    if (!has("exemplars")) {
                        add("exemplars", JsonArray().also { outer ->
                            outer.add(JsonArray().also { inner ->
                                for (i in 0 until patchEmbArr.size()) inner.add(patchEmbArr[i])
                            })
                        })
                        addProperty("num_exemplars", 1)
                    }
                })
                newCount++
            } else {
                // Existing — add to field_embedding only; anchor is NEVER modified.
                val existing = currentIndividuals.getAsJsonObject(name)
                val anchorArr = existing.getAsJsonArray("embedding")
                if (anchorArr == null) { skippedCount++; continue }
                val anchor = FloatArray(anchorArr.size()) { anchorArr[it].asFloat }
                val dim = minOf(anchor.size, patchProto.size)

                // Quality gate: reject patch if it's too far from the anchor
                val qualityGate = prefs.unknownThreshold * 0.5f
                val sim = EmbeddingUtils.dotProduct(patchProto, anchor)
                if (sim < qualityGate) {
                    Log.w(TAG, "importPatch: '$name' patch sim=${"%.3f".format(sim)} < gate=${"%.3f".format(qualityGate)}, skipped")
                    skippedCount++
                    continue
                }

                // Cap: ignore if field_crops already at max
                val existingFieldCrops = existing.get("field_crops")?.asInt ?: 0
                if (existingFieldCrops >= MAX_FIELD_CROPS) {
                    Log.i(TAG, "importPatch: '$name' field_crops cap reached, skipped")
                    skippedCount++
                    continue
                }

                // Weighted merge into field_embedding
                val existingFieldArr = existing.getAsJsonArray("field_embedding")
                val currentField = existingFieldArr
                    ?.takeIf { it.size() == dim }
                    ?.let { FloatArray(dim) { i -> it[i].asFloat } }
                val newFieldCrops = existingFieldCrops + patchCrops

                val merged = FloatArray(dim)
                if (currentField != null && existingFieldCrops > 0) {
                    for (i in 0 until dim) {
                        merged[i] = (currentField[i] * existingFieldCrops + patchProto[i] * patchCrops) / newFieldCrops
                    }
                } else {
                    patchProto.copyInto(merged, endIndex = dim)
                }
                val normed = EmbeddingUtils.l2Normalize(merged)

                val fieldArr = JsonArray()
                normed.forEach { fieldArr.add(it) }
                existing.add("field_embedding", fieldArr)
                existing.addProperty("field_crops", newFieldCrops)
                existing.addProperty("last_merged_at", isoNow())
                currentIndividuals.add(name, existing)
                updatedCount++
            }
        }

        galleryJson.add("individuals", currentIndividuals)
        val bytes = galleryJson.toString().toByteArray(Charsets.UTF_8)
        modelManager.installModelFile(bytes, ModelManager.GALLERY_FILENAME)
        Log.i(TAG, "Patch imported — new=$newCount updated=$updatedCount skipped=$skippedCount")
        return ImportResult(newCount, updatedCount, skippedCount)
    }

    // ── Backup history ────────────────────────────────────────────────────────

    /**
     * One entry in the gallery history list.
     *
     * The diff fields describe changes made AFTER this backup was created
     * (i.e., what you would lose by restoring it):
     *   [addedAfter]         — individuals added to the current gallery since this backup
     *   [removedAfter]       — individuals removed from the current gallery since this backup
     *   [fieldUpdatesAfter]  — field crop count changes, e.g. "Sinta +3 field"
     */
    data class BackupEntry(
        val file: File,
        val timestamp: Long,
        val individualCount: Int,
        val addedAfter: List<String>,
        val removedAfter: List<String>,
        val fieldUpdatesAfter: List<String>
    ) {
        val isCurrentState: Boolean get() =
            addedAfter.isEmpty() && removedAfter.isEmpty() && fieldUpdatesAfter.isEmpty()

        /**
         * Short human-readable description of what changed after this backup.
         * Limits output to the most relevant items to fit a single list row.
         */
        fun changesSummary(): String {
            if (isCurrentState) return "Matches current gallery"
            val parts = mutableListOf<String>()
            addedAfter.take(2).forEach    { parts.add("+ $it") }
            removedAfter.take(2).forEach  { parts.add("− $it") }
            fieldUpdatesAfter.take(2).forEach { parts.add(it) }
            val total = addedAfter.size + removedAfter.size + fieldUpdatesAfter.size
            val extra = total - parts.size
            return parts.joinToString("  ·  ") + if (extra > 0) "  … +$extra more" else ""
        }
    }

    /**
     * Returns all gallery backups sorted newest-first, each annotated with a diff
     * against the current gallery so the caller can show informative history.
     */
    fun listBackupsWithDiff(): List<BackupEntry> {
        val files = modelManager.listGalleryBackups()
        val current = try { readCurrentGalleryJson() } catch (e: Exception) { return emptyList() }
        val currentIndividuals = current.getAsJsonObject("individuals") ?: JsonObject()

        return files.mapNotNull { file ->
            try {
                val backupJson   = JsonParser.parseString(file.readText()).asJsonObject
                val backupInds   = backupJson.getAsJsonObject("individuals") ?: JsonObject()
                val timestamp    = parseBackupTimestamp(file)

                val addedAfter        = mutableListOf<String>()
                val removedAfter      = mutableListOf<String>()
                val fieldUpdatesAfter = mutableListOf<String>()

                // In current but absent from backup → was added after this backup
                currentIndividuals.keySet().sorted().forEach { name ->
                    if (!backupInds.has(name)) addedAfter.add(name)
                }
                // In backup but absent from current → was removed after this backup
                backupInds.keySet().sorted().forEach { name ->
                    if (!currentIndividuals.has(name)) removedAfter.add(name)
                }
                // Field crop count changes
                backupInds.keySet().sorted().forEach { name ->
                    if (currentIndividuals.has(name)) {
                        val before = backupInds.getAsJsonObject(name)
                            .get("field_crops")?.asInt ?: 0
                        val after  = currentIndividuals.getAsJsonObject(name)
                            .get("field_crops")?.asInt ?: 0
                        val delta  = after - before
                        if (delta != 0) {
                            fieldUpdatesAfter.add("$name ${if (delta > 0) "+$delta" else "$delta"} field")
                        }
                    }
                }

                BackupEntry(
                    file              = file,
                    timestamp         = timestamp,
                    individualCount   = backupInds.size(),
                    addedAfter        = addedAfter,
                    removedAfter      = removedAfter,
                    fieldUpdatesAfter = fieldUpdatesAfter
                )
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read backup ${file.name}: ${e.message}")
                null
            }
        }
    }

    private fun parseBackupTimestamp(file: File): Long {
        val dateStr = file.name.removePrefix("gallery_").removeSuffix(".json")
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dateStr)?.time
                ?: file.lastModified()
        } catch (e: Exception) {
            file.lastModified()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Reads the active gallery JSON, resolving the file in the same priority order
     * as ModelManager: user gallery.json → user embeddings.json → assets.
     * Returns a mutable [JsonObject] so the caller can modify it.
     */
    private fun readCurrentGalleryJson(): JsonObject {
        val modelsDir = FileUtils.getModelsDir(context)

        val text: String = when {
            File(modelsDir, ModelManager.GALLERY_FILENAME).exists() ->
                File(modelsDir, ModelManager.GALLERY_FILENAME).readText()
            File(modelsDir, ModelManager.EMBEDDINGS_FILENAME).exists() ->
                File(modelsDir, ModelManager.EMBEDDINGS_FILENAME).readText()
            else -> {
                try {
                    context.assets.open(ModelManager.GALLERY_FILENAME).bufferedReader().readText()
                } catch (_: Exception) {
                    try {
                        context.assets.open(ModelManager.EMBEDDINGS_FILENAME).bufferedReader().readText()
                    } catch (_: Exception) {
                        // Return a minimal empty gallery
                        val dim = try { modelManager.getEmbeddings().embeddingDim } catch (_: Exception) { 768 }
                        val thr = try { modelManager.getEmbeddings().unknownThreshold } catch (_: Exception) { 0.22f }
                        return JsonObject().apply {
                            addProperty("version",           "app-created")
                            addProperty("embedding_dim",     dim)
                            addProperty("unknown_threshold", thr)
                            add("individuals", JsonObject())
                        }
                    }
                }
            }
        }
        return JsonParser.parseString(text).asJsonObject
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    /**
     * Rejects names that are empty, too long, or contain characters that could
     * cause issues in JSON keys or UI rendering.
     */
    private fun isValidIndividualName(name: String): Boolean =
        name.isNotBlank() &&
        name.length <= 64 &&
        name.all { it.isLetterOrDigit() || it in " -_.'()" }
}
