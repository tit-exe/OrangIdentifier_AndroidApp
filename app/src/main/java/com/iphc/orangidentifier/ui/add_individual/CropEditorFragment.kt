package com.iphc.orangidentifier.ui.add_individual

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.ui.box_editor.BoxEditorView
import com.iphc.orangidentifier.utils.BitmapUtils
import com.iphc.orangidentifier.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen crop editor for adjusting a single face box.
 *
 * Receives [cropIndex] via nav args → reads CropItem from AddIndividualViewModel.
 * Shows the original image with the existing bounding box in a BoxEditorView.
 * On confirm: re-crops with the adjusted box and updates the ViewModel.
 */
@AndroidEntryPoint
class CropEditorFragment : BaseFragment(R.layout.fragment_crop_editor) {

    private val viewModel: AddIndividualViewModel by activityViewModels()

    private lateinit var boxEditorView: BoxEditorView
    private var originalBitmap: Bitmap? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cropIndex = arguments?.getInt("cropIndex") ?: -1
        val item = viewModel.cropItems.value.getOrNull(cropIndex)

        if (item == null) {
            // Should never happen — pop gracefully
            findNavController().popBackStack()
            return
        }

        boxEditorView = view.findViewById(R.id.box_editor)

        view.findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            findNavController().popBackStack()
        }

        view.findViewById<Button>(R.id.btn_confirm_crop).setOnClickListener {
            confirmCrop(cropIndex)
        }

        // Load original image from source URI
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                BitmapUtils.decodeUri(requireContext(), item.sourceUri)
            }
            if (bmp == null) {
                if (isAdded) toast("Cannot load original image")
                if (isAdded) findNavController().popBackStack()
                return@launch
            }
            originalBitmap = bmp
            // BoxEditorView receives the image + the existing box to allow resizing
            boxEditorView.setBitmap(bmp, listOf(item.boundingBox))
        }
    }

    private fun confirmCrop(cropIndex: Int) {
        val boxes = boxEditorView.getBoxesInImageCoords()
        if (boxes.isEmpty()) {
            toast("No box defined")
            return
        }
        val newBox = boxes[0]

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                viewModel.updateCrop(cropIndex, newBox)
            }
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        boxEditorView.clear()   // release bitmap reference
        originalBitmap = null
        super.onDestroyView()
    }
}
