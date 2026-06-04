package com.iphc.orangidentifier.ui.result

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.data.repository.GalleryManager
import com.iphc.orangidentifier.domain.model.Detection
import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.domain.usecase.GetScanHistoryUseCase
import com.iphc.orangidentifier.ui.MainViewModel
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.utils.toast
import com.iphc.orangidentifier.utils.toFormattedDateTime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ScanResultFragment : BaseFragment(R.layout.fragment_scan_result) {

    @Inject lateinit var getScanHistoryUseCase: GetScanHistoryUseCase
    @Inject lateinit var galleryManager: GalleryManager

    private val mainViewModel: MainViewModel by activityViewModels()

    // Currently displayed scan (can change as user navigates the batch)
    private var currentScanId = -1L
    private var loadScanJob: Job? = null

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var ivResult:            ImageView
    private lateinit var tvSummary:           TextView
    private lateinit var tvTimestamp:         TextView
    private lateinit var containerFaces:      LinearLayout
    private lateinit var btnNewScan:          Button
    private lateinit var btnEditBoxes:        Button
    private lateinit var batchNavBar:         LinearLayout
    private lateinit var tvBatchProgress:     TextView
    private lateinit var btnNextScan:         Button
    private lateinit var batchLoadingOverlay: LinearLayout

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ivResult            = view.findViewById(R.id.iv_result)
        tvSummary           = view.findViewById(R.id.tv_summary)
        tvTimestamp         = view.findViewById(R.id.tv_timestamp)
        containerFaces      = view.findViewById(R.id.container_faces)
        btnNewScan          = view.findViewById(R.id.btn_new_scan)
        btnEditBoxes        = view.findViewById(R.id.btn_edit_boxes)
        batchNavBar         = view.findViewById(R.id.batch_nav_bar)
        tvBatchProgress     = view.findViewById(R.id.tv_batch_progress)
        btnNextScan         = view.findViewById(R.id.btn_next_scan)
        batchLoadingOverlay = view.findViewById(R.id.batch_loading_overlay)

        currentScanId = arguments?.getLong("scanId") ?: -1L

        btnNewScan.setOnClickListener {
            mainViewModel.clearBatch()
            findNavController().popBackStack()
        }

        btnEditBoxes.setOnClickListener {
            findNavController().navigate(
                R.id.action_scan_result_to_box_editor,
                bundleOf("scanId" to currentScanId)
            )
        }

        btnNextScan.setOnClickListener {
            mainViewModel.advanceBatchIndex()
        }

        // Initial load
        loadScan(currentScanId)

        // Observe batch session changes (handles "next" navigation reactively)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.batchSession.collect { session ->
                    handleBatchSession(session)
                }
            }
        }
    }

    // ── Batch session handler ─────────────────────────────────────────────────

    private fun handleBatchSession(session: MainViewModel.BatchSession?) {
        if (session == null) {
            // Single-image scan or batch cleared
            batchNavBar.visibility         = View.GONE
            batchLoadingOverlay.visibility = View.GONE
            return
        }

        batchNavBar.visibility = View.VISIBLE

        val completedCount = session.completedIds.size
        val idx = session.currentViewingIndex
        val nextIdAvailable = idx < completedCount
        val moreExpected = session.isProcessingMore

        // Progress text: "Result 2 / 5  (processing…)"
        val label = buildString {
            append("Result ${if (nextIdAvailable) idx + 1 else "…"}")
            if (completedCount > 0) append(" / $completedCount")
            if (moreExpected)       append("  (processing…)")
        }
        tvBatchProgress.text = label

        // Determine if there's a "next" to show
        val hasNext = idx + 1 < completedCount || moreExpected
        btnNextScan.visibility = if (hasNext) View.VISIBLE else View.GONE

        // If the user has advanced past the last completed result, show overlay
        if (!nextIdAvailable && moreExpected) {
            batchLoadingOverlay.visibility = View.VISIBLE
            return
        }

        batchLoadingOverlay.visibility = View.GONE

        // Reactively load if the current viewing index points to a new scan
        val targetId = session.completedIds.getOrNull(idx) ?: return
        if (targetId != currentScanId) {
            currentScanId = targetId
            loadScan(currentScanId)
        }
    }

    // ── Scan loading ──────────────────────────────────────────────────────────

    private fun loadScan(scanId: Long) {
        loadScanJob?.cancel()
        loadScanJob = viewLifecycleOwner.lifecycleScope.launch {
            val scans = getScanHistoryUseCase.execute().firstOrNull() ?: return@launch
            val scan  = scans.find { it.id == scanId } ?: return@launch

            tvTimestamp.text = scan.timestamp.toFormattedDateTime()
            tvSummary.text   = buildSummaryText(scan)

            withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp  = BitmapFactory.decodeFile(scan.imagePath, opts)
                withContext(Dispatchers.Main) {
                    if (bmp != null) ivResult.setImageBitmap(bmp)
                    // else: file was deleted — silently keep placeholder
                }
            }

            val ordered = scan.detections
                .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

            containerFaces.removeAllViews()
            ordered.forEachIndexed { index, detection ->
                containerFaces.addView(buildFaceCard(index + 1, detection))
            }

            // Update edit-boxes button with current scan id
            btnEditBoxes.setOnClickListener {
                findNavController().navigate(
                    R.id.action_scan_result_to_box_editor,
                    bundleOf("scanId" to scanId)
                )
            }
        }
    }

    // ── Face card builder ─────────────────────────────────────────────────────

    private fun buildSummaryText(scan: ScanRecord): String {
        val n = scan.detections.size
        val faceStr = if (n == 1) "1 face detected" else "$n faces detected"
        return "$faceStr · ${scan.durationMs}ms · ${scan.sourceType.name.lowercase()}"
    }

    private fun buildFaceCard(faceNumber: Int, detection: Detection): View {
        val card = layoutInflater.inflate(R.layout.item_detection_result, null)

        card.findViewById<TextView>(R.id.tv_face_number).text = "Face $faceNumber"

        val top1         = detection.topPredictions.firstOrNull()
        val nameView     = card.findViewById<TextView>(R.id.tv_top_name)
        val labelView    = card.findViewById<TextView>(R.id.tv_confidence_label)
        val progressBar  = card.findViewById<ProgressBar>(R.id.pb_confidence)
        val detailsView  = card.findViewById<TextView>(R.id.tv_top3_details)
        val btnCorrect   = card.findViewById<Button>(R.id.btn_correct_id)

        btnCorrect.setOnClickListener  { showCorrectionDialog() }

        if (detection.isUnknown) {
            nameView.text  = getString(R.string.label_unknown)
            nameView.setTextColor(requireContext().getColor(R.color.unknown))
            labelView.visibility = View.GONE
            progressBar.progress = (top1?.confidence?.times(100))?.toInt() ?: 0
            progressBar.progressTintList =
                android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.unknown))
            val sb = StringBuilder("similarity: ${"%.1f%%".format((top1?.confidence ?: 0f) * 100)}\n\n")
            detection.topPredictions.forEach { pred ->
                sb.append("${pred.individualName}  ${"%.3f".format(pred.confidence)}\n")
            }
            detailsView.text = sb.toString().trimEnd()

        } else {
            val topConf = top1?.confidence ?: 0f
            nameView.text = top1?.individualName ?: "Unknown"
            nameView.setTextColor(requireContext().getColor(R.color.text_primary))

            val (labelText, labelColor) = confidenceLabelAndColor(topConf)
            labelView.text = labelText
            labelView.setTextColor(requireContext().getColor(labelColor))
            labelView.visibility = View.VISIBLE

            progressBar.progress = (topConf * 100).toInt()
            progressBar.progressTintList =
                android.content.res.ColorStateList.valueOf(requireContext().getColor(labelColor))

            val sb = StringBuilder()
            detection.topPredictions.forEach { pred ->
                sb.append("#${pred.rank}  ${pred.individualName}  —  ${"%.1f%%".format(pred.confidence * 100)}\n")
            }
            detailsView.text = sb.toString().trimEnd()
        }

        return card
    }

    private fun confidenceLabelAndColor(confidence: Float): Pair<String, Int> = when {
        confidence >= 0.70f -> Pair(getString(R.string.confidence_label_high),   R.color.confidence_high)
        confidence >= 0.40f -> Pair(getString(R.string.confidence_label_medium), R.color.confidence_medium)
        else                -> Pair(getString(R.string.confidence_label_low),    R.color.confidence_low)
    }

    // ── Correct ID / Confirm Unknown ─────────────────────────────────────────

    private fun showCorrectionDialog() {
        val names = galleryManager.listIndividuals()
        val options = arrayOf("⚑  Confirm as Unknown (not in gallery)") + names.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Who is this individual?")
            .setItems(options) { _, which ->
                if (which == 0) {
                    toast("Confirmed as Unknown — gallery unchanged.")
                } else {
                    val name = names[which - 1]
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Improve recognition of $name")
                        .setMessage(
                            "Go to Gallery → $name → Add Photos\n\n" +
                            "Add 10–20 direct camera photos to improve recognition. " +
                            "A single scan image is not enough and can corrupt the profile."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
