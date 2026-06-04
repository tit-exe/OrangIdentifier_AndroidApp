package com.iphc.orangidentifier.ui.add_individual

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Displays all detected face crops in a 3-column grid.
 * The ranger can delete bad crops (wrong individual, bad angle, etc.)
 * before confirming addition to the gallery.
 */
@AndroidEntryPoint
class CropReviewFragment : BaseFragment(R.layout.fragment_crop_review) {

    private val viewModel: AddIndividualViewModel by activityViewModels()

    private lateinit var tvTitle:          TextView
    private lateinit var tvQualityBanner:  TextView
    private lateinit var rvCrops:          RecyclerView
    private lateinit var layoutComputing:  LinearLayout
    private lateinit var btnConfirm:       Button

    private val cropAdapter = CropAdapter(
        onDelete = { item -> viewModel.removeCrop(item) },
        onEdit   = { item ->
            // Find the index of this item and navigate to the crop editor
            val index = viewModel.cropItems.value.indexOf(item)
            if (index >= 0) {
                findNavController().navigate(
                    R.id.action_crop_review_to_crop_editor,
                    bundleOf("cropIndex" to index)
                )
            }
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            findNavController().popBackStack()
        }

        tvTitle         = view.findViewById(R.id.tv_title)
        tvQualityBanner = view.findViewById(R.id.tv_quality_banner)
        rvCrops         = view.findViewById(R.id.rv_crops)
        layoutComputing = view.findViewById(R.id.layout_computing)
        btnConfirm      = view.findViewById(R.id.btn_confirm)

        tvTitle.text = "Review — ${viewModel.name.value}"

        rvCrops.layoutManager = GridLayoutManager(requireContext(), 3)
        rvCrops.adapter       = cropAdapter

        btnConfirm.setOnClickListener { checkAndConfirm() }

        observeState()
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cropItems.collect { items ->
                        cropAdapter.submitList(items)
                        updateQualityBanner(items.size)
                        btnConfirm.isEnabled = items.size >= AddIndividualViewModel.MIN_CROPS_HARD
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is AddIndividualViewModel.UiState.ComputingEmbeddings -> {
                                layoutComputing.visibility = View.VISIBLE
                                btnConfirm.isEnabled = false
                            }
                            is AddIndividualViewModel.UiState.SimilarityWarning -> {
                                layoutComputing.visibility = View.GONE
                                btnConfirm.isEnabled = true
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Possible duplicate detected")
                                    .setMessage(
                                        "This individual looks very similar to '${state.similarName}' " +
                                        "(similarity: ${"%.0f%%".format(state.similarity * 100)}).\n\n" +
                                        "Are you sure this is a different individual?"
                                    )
                                    .setPositiveButton("Yes, different individual") { _, _ ->
                                        viewModel.confirmAddDespiteWarning(state.pendingEmbeddings)
                                    }
                                    .setNegativeButton("Cancel") { _, _ -> viewModel.resetState() }
                                    .setCancelable(false)
                                    .show()
                            }
                            is AddIndividualViewModel.UiState.QualityGateRejection -> {
                                layoutComputing.visibility = View.GONE
                                btnConfirm.isEnabled = true
                                showQualityRejectionDialog(state.individualName)
                            }
                            is AddIndividualViewModel.UiState.Success -> {
                                layoutComputing.visibility = View.GONE
                                showSuccessDialog(state.name)
                            }
                            is AddIndividualViewModel.UiState.Error -> {
                                layoutComputing.visibility = View.GONE
                                btnConfirm.isEnabled = true
                                toast(state.message)
                                viewModel.resetState()
                            }
                            else -> {
                                layoutComputing.visibility = View.GONE
                                btnConfirm.isEnabled =
                                    viewModel.cropItems.value.size >= AddIndividualViewModel.MIN_CROPS_HARD
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Confirm flow ──────────────────────────────────────────────────────────

    private fun checkAndConfirm() {
        val name = viewModel.name.value
        if (viewModel.individualExists(name)) {
            val count = viewModel.cropItems.value.size
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add crops to $name?")
                .setMessage(
                    "$count new crop${if (count > 1) "s" else ""} will be merged into $name's existing prototype.\n\n" +
                    "The original training data is preserved — these crops add to it via weighted average."
                )
                .setPositiveButton("Add crops") { _, _ -> viewModel.confirmAndAdd() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            viewModel.confirmAndAdd()
        }
    }

    // ── Success dialog ────────────────────────────────────────────────────────

    private fun showSuccessDialog(individualName: String) {
        val isFieldAdd = viewModel.lockedName.value.isNotBlank()
        val cropCount  = viewModel.cropItems.value.size

        if (isFieldAdd) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Field photos added ✓")
                .setMessage(
                    "$cropCount crop${if (cropCount != 1) "s" else ""} added as field data for '$individualName'.\n\n" +
                    "The original training prototype is preserved."
                )
                .setPositiveButton("Done") { _, _ -> navigateDone() }
                .setCancelable(false)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Added to gallery ✓")
                .setMessage("'$individualName' has been added with $cropCount crop${if (cropCount != 1) "s" else ""}.")
                .setPositiveButton("Share with colleagues") { _, _ -> sharePatch(individualName) }
                .setNegativeButton("Done") { _, _ -> navigateDone() }
                .setCancelable(false)
                .show()
        }
    }

    private fun sharePatch(individualName: String) {
        val patchFile = viewModel.exportPatch(individualName)
        if (patchFile == null) {
            toast("Could not create patch file")
            navigateDone()
            return
        }
        val intent = viewModel.createShareIntent(patchFile)
        startActivity(Intent.createChooser(intent, "Share gallery patch"))
        navigateDone()
    }

    private fun navigateDone() {
        val wasFieldAdd = viewModel.lockedName.value.isNotBlank()
        viewModel.clearAll()
        viewModel.clearLock()   // reset lock state after navigation
        if (wasFieldAdd) {
            findNavController().popBackStack(R.id.nav_gallery, false)
        } else {
            findNavController().popBackStack(R.id.nav_home, false)
        }
    }

    // ── Quality gate rejection dialog ────────────────────────────────────────

    private fun showQualityRejectionDialog(name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Photos not saved — would corrupt profile")
            .setMessage(
                "These photos were not added to $name's profile.\n\n" +
                "After merging, the AI checked the result and found that it would either:\n\n" +
                "• make the profile too generic (appears as Unknown in classification), or\n" +
                "• cause another individual to be mistakenly identified as $name.\n\n" +
                "This is a safety check — it prevents one or two bad photos from " +
                "breaking the recognition for everyone.\n\n" +
                "What works:\n" +
                "Collect 10–20 direct camera photos of $name in different conditions. " +
                "Adding them all together produces a stronger average that passes the check, " +
                "even if each individual photo seems uncertain."
            )
            .setPositiveButton("Got it") { _, _ -> viewModel.resetState() }
            .setCancelable(false)
            .show()
    }

    // ── Quality UI ────────────────────────────────────────────────────────────

    private fun updateQualityBanner(count: Int) {
        val (text, color) = when {
            count < AddIndividualViewModel.MIN_CROPS_HARD ->
                Pair("$count crops — minimum ${AddIndividualViewModel.MIN_CROPS_HARD} required", R.color.confidence_low)
            count < AddIndividualViewModel.MIN_CROPS_WARN ->
                Pair("$count crops — OK, but 10+ recommended", R.color.confidence_medium)
            else -> Pair("$count crops — quality: Good ✓", R.color.confidence_high)
        }
        tvQualityBanner.text = text
        tvQualityBanner.setTextColor(requireContext().getColor(color))
        tvQualityBanner.setBackgroundColor(requireContext().getColor(R.color.surface_secondary))
    }
}
