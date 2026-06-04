package com.iphc.orangidentifier.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.ui.MainViewModel
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class HomeFragment : BaseFragment(R.layout.fragment_home) {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var btnCamera:        Button
    private lateinit var btnGallery:       Button
    private lateinit var btnAddIndividual: Button
    private lateinit var progressBar:      ProgressBar
    private lateinit var tvStatus:         TextView

    private var currentPhotoUri: Uri? = null

    // ── Activity result launchers ─────────────────────────────────────────────

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            currentPhotoUri?.let { viewModel.processImage(it, ScanRecord.SourceType.CAMERA) }
        }
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.size == 1) {
            viewModel.processImage(uris[0], ScanRecord.SourceType.GALLERY)
        } else if (uris.size > 1) {
            viewModel.processBatch(uris, ScanRecord.SourceType.GALLERY)
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else showPermissionDeniedDialog(
                title   = "Camera permission required",
                message = "OrangIdentifier needs camera access to photograph primates for identification.\n\n" +
                          "Go to Settings → Apps → OrangIdentifier → Permissions → Camera to enable it."
            )
        }

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pickImages.launch("image/*")
            else showPermissionDeniedDialog(
                title   = "Storage permission required",
                message = "OrangIdentifier needs access to your photos to select images for scanning.\n\n" +
                          "Go to Settings → Apps → OrangIdentifier → Permissions → Photos to enable it."
            )
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Restore camera URI in case the process was killed while the camera was open
        @Suppress("DEPRECATION")
        currentPhotoUri = savedInstanceState?.getParcelable(KEY_CAMERA_URI)

        btnCamera        = view.findViewById(R.id.btn_camera)
        btnGallery       = view.findViewById(R.id.btn_gallery)
        btnAddIndividual = view.findViewById(R.id.btn_add_individual)
        progressBar      = view.findViewById(R.id.progress_bar)
        tvStatus         = view.findViewById(R.id.tv_status)

        btnCamera.setOnClickListener        { checkCameraPermissionAndLaunch() }
        btnGallery.setOnClickListener       { checkStoragePermissionAndLaunch() }
        btnAddIndividual.setOnClickListener {
            findNavController().navigate(R.id.action_nav_home_to_add_individual)
        }

        observeInferenceState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPhotoUri?.let { outState.putParcelable(KEY_CAMERA_URI, it) }
    }

    // ── Permission flows ─────────────────────────────────────────────────────

    private fun checkCameraPermissionAndLaunch() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> openCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Camera access needed")
                    .setMessage(
                        "OrangIdentifier photographs primate faces to identify individuals.\n\n" +
                        "Everything is processed on-device — no data leaves the phone."
                    )
                    .setPositiveButton("Grant access") { _, _ ->
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun checkStoragePermissionAndLaunch() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(requireContext(), permission)
                    == PackageManager.PERMISSION_GRANTED -> pickImages.launch("image/*")
            shouldShowRequestPermissionRationale(permission) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Photo access needed")
                    .setMessage("OrangIdentifier needs access to your photos to select images for scanning.")
                    .setPositiveButton("Grant access") { _, _ -> requestStoragePermission.launch(permission) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> requestStoragePermission.launch(permission)
        }
    }

    private fun openCamera() {
        val dir  = File(requireContext().cacheDir, "temp_photos").also { it.mkdirs() }
        val file = File(dir, "temp_photo_${System.currentTimeMillis()}.jpg")
        currentPhotoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        takePicture.launch(currentPhotoUri)
    }

    private fun showPermissionDeniedDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ── State observers ───────────────────────────────────────────────────────

    private fun observeInferenceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.inferenceState.collect { state ->
                    when (state) {
                        is MainViewModel.InferenceState.Idle -> {
                            progressBar.visibility = View.GONE
                            tvStatus.visibility    = View.GONE
                            setButtonsEnabled(true)
                        }
                        is MainViewModel.InferenceState.Loading -> {
                            progressBar.visibility = View.VISIBLE
                            tvStatus.visibility    = View.VISIBLE
                            tvStatus.text = getString(R.string.status_processing)
                            setButtonsEnabled(false)
                        }
                        is MainViewModel.InferenceState.Success -> {
                            viewModel.resetInferenceState()
                            findNavController().navigate(
                                R.id.nav_scan_result,
                                bundleOf("scanId" to state.recordId)
                            )
                        }
                        is MainViewModel.InferenceState.Error -> {
                            viewModel.resetInferenceState()
                            toast(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnCamera.isEnabled  = enabled
        btnGallery.isEnabled = enabled
        // Add Individual stays enabled even during scan processing
    }

    companion object {
        private const val KEY_CAMERA_URI = "camera_uri"
    }
}
