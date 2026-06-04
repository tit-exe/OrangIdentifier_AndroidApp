package com.iphc.orangidentifier.ui.add_individual

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class AddIndividualFragment : BaseFragment(R.layout.fragment_add_individual) {

    private val viewModel: AddIndividualViewModel by activityViewModels()

    private lateinit var tvTitle:          TextView
    private lateinit var etName:           EditText
    private lateinit var btnTakePhoto:     Button
    private lateinit var btnPickGallery:   Button
    private lateinit var btnReview:        Button
    private lateinit var tvQuality:        TextView
    private lateinit var layoutProcessing: LinearLayout
    private lateinit var rvPreview:        RecyclerView

    private val previewAdapter = CropAdapter(
        onDelete = { item -> viewModel.removeCrop(item) },
        onEdit   = null  // no editor in the small horizontal preview strip
    )

    private var currentPhotoUri: Uri? = null

    // ── Launchers ─────────────────────────────────────────────────────────────

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) currentPhotoUri?.let { viewModel.processPhoto(it) }
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.processPhotos(uris)
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                // Permanently denied — guide user to Settings
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Camera access required")
                    .setMessage(
                        "Camera permission was denied.\n\n" +
                        "To enable it: Settings → Apps → OrangIdentifier → Permissions → Camera."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                toast("Camera permission required")
            }
        }

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pickImages.launch("image/*")
            else toast("Storage permission required to pick photos")
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("DEPRECATION")
        currentPhotoUri = savedInstanceState?.getParcelable(KEY_CAMERA_URI)

        tvTitle          = view.findViewById(R.id.tv_title)
        etName           = view.findViewById(R.id.et_name)
        view.findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            findNavController().popBackStack()
        }
        btnTakePhoto     = view.findViewById(R.id.btn_take_photo)
        btnPickGallery   = view.findViewById(R.id.btn_pick_gallery)
        btnReview        = view.findViewById(R.id.btn_review)
        tvQuality        = view.findViewById(R.id.tv_quality)
        layoutProcessing = view.findViewById(R.id.layout_processing)
        rvPreview        = view.findViewById(R.id.rv_crops_preview)

        // Horizontal strip for preview
        rvPreview.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        rvPreview.adapter = previewAdapter

        // Locked mode: launched from Gallery to add field photos to an existing individual
        val lockedName = arguments?.getString("lockedIndividualName").orEmpty()
        if (lockedName.isNotBlank()) {
            viewModel.lockToExisting(lockedName)
            tvTitle.text = "Add Photos — $lockedName"
            etName.isEnabled = false
            etName.alpha = 0.5f
        } else {
            viewModel.clearLock()
            tvTitle.setText(R.string.add_individual_title)
        }

        // Restore name from ViewModel (set by lockToExisting or previous session)
        etName.setText(viewModel.name.value)

        etName.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveNameAndHideKeyboard(v)
                true
            } else false
        }
        etName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.setName(etName.text.toString())
        }

        btnTakePhoto.setOnClickListener   { checkCameraAndLaunch() }
        btnPickGallery.setOnClickListener { checkStorageAndLaunch() }
        btnReview.setOnClickListener      { navigateToReview() }

        observeState()
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cropItems.collect { items ->
                        previewAdapter.submitList(items)
                        updateQualityIndicator(items.size)
                        btnReview.isEnabled = items.isNotEmpty()
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is AddIndividualViewModel.UiState.ProcessingPhoto -> {
                                layoutProcessing.visibility = View.VISIBLE
                                setButtonsEnabled(false)
                            }
                            is AddIndividualViewModel.UiState.Error -> {
                                layoutProcessing.visibility = View.GONE
                                setButtonsEnabled(true)
                                toast(state.message)
                                viewModel.resetState()
                            }
                            else -> {
                                layoutProcessing.visibility = View.GONE
                                setButtonsEnabled(true)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun checkCameraAndLaunch() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> openCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Camera access needed")
                    .setMessage("The camera is needed to photograph the individual for identification.")
                    .setPositiveButton("Grant") { _, _ ->
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun checkStorageAndLaunch() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            pickImages.launch("image/*")
        } else {
            requestStoragePermission.launch(permission)
        }
    }

    private fun openCamera() {
        val dir  = File(requireContext().cacheDir, "temp_photos").also { it.mkdirs() }
        val file = File(dir, "add_photo_${System.currentTimeMillis()}.jpg")
        currentPhotoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        takePicture.launch(currentPhotoUri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPhotoUri?.let { outState.putParcelable(KEY_CAMERA_URI, it) }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToReview() {
        viewModel.setName(etName.text.toString())
        if (viewModel.name.value.isBlank()) {
            etName.error = "Please enter a name"
            etName.requestFocus()
            return
        }
        findNavController().navigate(R.id.action_add_individual_to_crop_review)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateQualityIndicator(count: Int) {
        val (text, color) = when {
            count == 0          -> Pair("No crops detected yet — take photos to start", R.color.text_secondary)
            count < AddIndividualViewModel.MIN_CROPS_HARD ->
                Pair("$count crops — minimum ${AddIndividualViewModel.MIN_CROPS_HARD} required to proceed", R.color.confidence_low)
            count < AddIndividualViewModel.MIN_CROPS_WARN ->
                Pair("$count crops — quality OK but 10+ recommended", R.color.confidence_medium)
            else                -> Pair("$count crops — quality: Good ✓", R.color.confidence_high)
        }
        tvQuality.text = text
        tvQuality.setTextColor(requireContext().getColor(color))
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnTakePhoto.isEnabled   = enabled
        btnPickGallery.isEnabled = enabled
    }

    private fun saveNameAndHideKeyboard(view: View) {
        viewModel.setName(etName.text.toString())
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    companion object {
        private const val KEY_CAMERA_URI = "camera_uri"
    }
}
