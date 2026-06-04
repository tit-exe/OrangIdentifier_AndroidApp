package com.iphc.orangidentifier.ui.box_editor

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.os.bundleOf
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.domain.repository.ScanRepository
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class BoxEditorFragment : BaseFragment(R.layout.fragment_box_editor) {

    @Inject lateinit var scanRepository: ScanRepository

    private val viewModel: BoxEditorViewModel by viewModels()

    private lateinit var editorView:    BoxEditorView
    private lateinit var btnAnalyze:    Button
    private lateinit var btnCancel:     Button
    private lateinit var fabAddBox:     FloatingActionButton
    private lateinit var fabDeleteBox:  FloatingActionButton
    private lateinit var tvBoxCount:    TextView
    private lateinit var progressBar:   ProgressBar

    private var scanId = -1L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scanId = arguments?.getLong("scanId") ?: -1L

        editorView   = view.findViewById(R.id.box_editor_view)
        btnAnalyze   = view.findViewById(R.id.btn_analyze)
        btnCancel    = view.findViewById(R.id.btn_cancel)
        fabAddBox    = view.findViewById(R.id.fab_add_box)
        fabDeleteBox = view.findViewById(R.id.fab_delete_box)
        tvBoxCount   = view.findViewById(R.id.tv_box_count)
        progressBar  = view.findViewById(R.id.progress_bar)

        editorView.listener = object : BoxEditorView.Listener {
            override fun onBoxCountChanged(count: Int) {
                updateCountLabel(count)
            }
            override fun onSelectionChanged(selectedIdx: Int) {
                // Show the delete FAB only when a box is selected and editing is enabled
                val hasSelection = selectedIdx >= 0
                fabDeleteBox.visibility = if (hasSelection) View.VISIBLE else View.GONE
            }
        }

        fabAddBox.setOnClickListener {
            if (editorView.boxes.size >= BoxEditorView.MAX_BOXES) {
                toast("Maximum ${BoxEditorView.MAX_BOXES} boxes")
            } else {
                editorView.addBox()
                updateCountLabel(editorView.boxes.size)
            }
        }

        fabDeleteBox.setOnClickListener {
            editorView.deleteSelected()
            // count label updated via onBoxCountChanged; FAB hidden via onSelectionChanged
        }

        btnCancel.setOnClickListener  { findNavController().popBackStack() }
        btnAnalyze.setOnClickListener { viewModel.analyze(scanId, editorView.getBoxesInImageCoords()) }

        loadScanData()
        observeState()
    }

    private fun loadScanData() {
        viewModel.loadScan(scanId)
        viewLifecycleOwner.lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) { scanRepository.getScanById(scanId) }
            if (scan == null) { toast("Scan not found"); findNavController().popBackStack(); return@launch }

            val imgPath = scan.originalImagePath.ifEmpty { scan.imagePath }
            val initialBoxes: List<RectF> = scan.detections.map { RectF(it.boundingBox) }

            withContext(Dispatchers.IO) {
                val bmp = BitmapFactory.decodeFile(imgPath)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        editorView.setBitmap(bmp, initialBoxes)
                        updateCountLabel(initialBoxes.size)
                    } else {
                        toast("Could not load image")
                        findNavController().popBackStack()
                    }
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is BoxEditorViewModel.UiState.Editing -> {
                            progressBar.visibility = View.GONE
                            setButtonsEnabled(true)
                        }
                        is BoxEditorViewModel.UiState.Analyzing -> {
                            progressBar.visibility = View.VISIBLE
                            setButtonsEnabled(false)
                        }
                        is BoxEditorViewModel.UiState.Done -> {
                            // Pop box editor AND the old scan result, navigate to refreshed result
                            findNavController().navigate(
                                R.id.nav_scan_result,
                                bundleOf("scanId" to state.scanId),
                                NavOptions.Builder()
                                    .setPopUpTo(R.id.nav_scan_result, inclusive = true)
                                    .build()
                            )
                        }
                        is BoxEditorViewModel.UiState.Error -> {
                            progressBar.visibility = View.GONE
                            setButtonsEnabled(true)
                            toast(state.message)
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }

    private fun setButtonsEnabled(on: Boolean) {
        btnAnalyze.isEnabled   = on && editorView.boxes.isNotEmpty()
        btnCancel.isEnabled    = on
        fabAddBox.isEnabled    = on
        fabDeleteBox.isEnabled = on
    }

    private fun updateCountLabel(count: Int) {
        tvBoxCount.text = "$count / ${BoxEditorView.MAX_BOXES}"
        btnAnalyze.isEnabled = count > 0
    }

    override fun onDestroyView() {
        // Release the bitmap held by BoxEditorView to prevent memory leaks
        editorView.clear()
        super.onDestroyView()
    }
}
