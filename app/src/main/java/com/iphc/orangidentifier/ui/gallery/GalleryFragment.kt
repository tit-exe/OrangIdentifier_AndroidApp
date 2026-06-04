package com.iphc.orangidentifier.ui.gallery

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.data.repository.GalleryManager
import com.iphc.orangidentifier.ui.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class GalleryFragment : BaseFragment(R.layout.fragment_gallery) {

    @Inject lateinit var galleryManager: GalleryManager

    private lateinit var rvIndividuals: RecyclerView
    private lateinit var tvEmpty:       TextView
    private lateinit var tvCount:       TextView
    private lateinit var btnUndo:       Button

    private val adapter = GalleryAdapter(
        onDelete    = { item -> showDeleteDialog(item) },
        onShare     = { item -> shareIndividual(item) },
        onAddPhotos = { item -> navigateToAddPhotos(item) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            findNavController().popBackStack()
        }

        rvIndividuals = view.findViewById(R.id.rv_individuals)
        tvEmpty       = view.findViewById(R.id.tv_empty)
        tvCount       = view.findViewById(R.id.tv_count)
        btnUndo       = view.findViewById(R.id.btn_undo_last_change)

        rvIndividuals.layoutManager = LinearLayoutManager(requireContext())
        rvIndividuals.adapter = adapter

        btnUndo.setOnClickListener { showUndoDialog() }

        loadGallery()
    }

    // ── Gallery load ──────────────────────────────────────────────────────────

    private fun loadGallery() {
        viewLifecycleOwner.lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) { galleryManager.getIndividualsSummary() }
            val hasBackup = withContext(Dispatchers.IO) { galleryManager.hasBackups() }

            tvCount.text = "${summaries.size} individual${if (summaries.size != 1) "s" else ""}"
            btnUndo.visibility = if (hasBackup) View.VISIBLE else View.GONE

            if (summaries.isEmpty()) {
                tvEmpty.visibility       = View.VISIBLE
                rvIndividuals.visibility = View.GONE
            } else {
                tvEmpty.visibility       = View.GONE
                rvIndividuals.visibility = View.VISIBLE
                adapter.submitList(summaries)
            }
        }
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    private fun showUndoDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Undo last gallery change?")
            .setMessage(
                "The gallery will be restored to its previous automatic backup.\n\n" +
                "Any changes made after that backup will be lost."
            )
            .setPositiveButton("Restore") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { galleryManager.undoLastGalleryChange() }
                    Snackbar.make(
                        requireView(),
                        if (ok) "Gallery restored" else "No backup available",
                        Snackbar.LENGTH_SHORT
                    ).show()
                    if (ok) loadGallery()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private fun showDeleteDialog(summary: GalleryManager.IndividualSummary) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove ${summary.name}?")
            .setMessage("'${summary.name}' will be removed. A backup is created automatically.")
            .setPositiveButton("Remove") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { galleryManager.removeIndividual(summary.name) }
                    loadGallery()
                    Snackbar.make(requireView(), "${summary.name} removed", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { showUndoDialog() }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Add field photos ──────────────────────────────────────────────────────

    private fun navigateToAddPhotos(summary: GalleryManager.IndividualSummary) {
        findNavController().navigate(
            R.id.action_gallery_to_add_individual,
            bundleOf("lockedIndividualName" to summary.name)
        )
    }

    // ── Share patch ───────────────────────────────────────────────────────────

    private fun shareIndividual(summary: GalleryManager.IndividualSummary) {
        viewLifecycleOwner.lifecycleScope.launch {
            val patchFile = withContext(Dispatchers.IO) {
                galleryManager.exportPatch(listOf(summary.name))
            }
            if (patchFile == null) {
                Snackbar.make(requireView(), "Could not create patch file", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent.createChooser(
                galleryManager.createShareIntent(patchFile),
                "Share ${summary.name}"
            ))
        }
    }
}
