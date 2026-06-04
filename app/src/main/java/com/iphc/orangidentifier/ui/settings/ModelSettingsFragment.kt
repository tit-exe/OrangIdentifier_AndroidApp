package com.iphc.orangidentifier.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.iphc.orangidentifier.R
import com.iphc.orangidentifier.data.local.prefs.AppPreferences
import com.iphc.orangidentifier.data.repository.GalleryManager
import com.iphc.orangidentifier.data.repository.ModelManager
import com.iphc.orangidentifier.domain.repository.ScanRepository
import com.iphc.orangidentifier.ui.base.BaseFragment
import com.iphc.orangidentifier.utils.FileUtils
import com.iphc.orangidentifier.utils.toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ModelSettingsFragment : BaseFragment(R.layout.fragment_model_settings) {

    @Inject lateinit var modelManager:    ModelManager
    @Inject lateinit var appPreferences:  AppPreferences
    @Inject lateinit var galleryManager:  GalleryManager
    @Inject lateinit var scanRepository:  ScanRepository

    private lateinit var etRangerName:        EditText
    private lateinit var tvDetectorVersion:   TextView
    private lateinit var tvClassifierVersion: TextView
    private lateinit var tvThreshold:         TextView
    private lateinit var seekbarThreshold:    SeekBar
    private lateinit var tvBackupInfo:        TextView

    // ── Activity result launchers ─────────────────────────────────────────────

    // ZIP bundle import (recommended — contains backbone + detector + gallery.json)
    private val importBundle = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { FileUtils.readUriToBytes(requireContext(), uri) }
            if (bytes == null) { toast("Could not read file."); return@launch }
            try {
                withContext(Dispatchers.IO) { modelManager.installZipBundle(bytes) }
                refreshDisplay()
                toast("Bundle installed. New models will be used for the next scan.")
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            }
        }
    }

    // Individual file imports (advanced)
    private val importDetector = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        installSingleModel(uri, ModelManager.DETECTOR_FILENAME)
    }

    private val importClassifier = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        installSingleModel(uri, ModelManager.BACKBONE_FILENAME)
    }

    // Patch import (.json diff from another ranger)
    private val importPatch = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { FileUtils.readUriToBytes(requireContext(), uri) }
            if (bytes == null) { toast("Could not read file."); return@launch }
            val result = withContext(Dispatchers.IO) { galleryManager.importPatch(bytes) }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Patch imported")
                .setMessage(
                    "${result.newCount} new individual${if (result.newCount != 1) "s" else ""}\n" +
                    "${result.updatedCount} updated\n" +
                    (if (result.skippedCount > 0) "${result.skippedCount} skipped (incompatible)" else "")
                )
                .setPositiveButton("OK", null)
                .show()
            refreshDisplay()
        }
    }

    // Import gallery.json directly
    private val importGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { FileUtils.readUriToBytes(requireContext(), uri) }
            if (bytes == null) { toast("Could not read file."); return@launch }
            withContext(Dispatchers.IO) {
                modelManager.installModelFile(bytes, ModelManager.GALLERY_FILENAME)
            }
            refreshDisplay()
            toast("Gallery updated.")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etRangerName        = view.findViewById(R.id.et_ranger_name)
        tvDetectorVersion   = view.findViewById(R.id.tv_detector_version)
        tvClassifierVersion = view.findViewById(R.id.tv_classifier_version)
        tvThreshold         = view.findViewById(R.id.tv_threshold)
        seekbarThreshold    = view.findViewById(R.id.seekbar_threshold)
        tvBackupInfo        = view.findViewById(R.id.tv_backup_info)

        // Threshold slider: progress 0–250 → 10%–35% (step 0.1%)
        // Clamp + write-back so the stored value always matches what the slider shows
        val currentThreshold = appPreferences.unknownThreshold.coerceIn(0.10f, 0.35f)
        if (appPreferences.unknownThreshold != currentThreshold) {
            appPreferences.unknownThreshold = currentThreshold
        }
        seekbarThreshold.progress = ((currentThreshold - 0.10f) * 1000f).toInt()
        seekbarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newThreshold = 0.10f + progress / 1000f
                appPreferences.unknownThreshold = newThreshold
                tvThreshold.text = thresholdLabel(newThreshold)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Restore saved ranger name
        etRangerName.setText(appPreferences.rangerName)
        etRangerName.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveRangerName()
                hideKeyboard(v)
                v.clearFocus()
                true
            } else false
        }
        etRangerName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveRangerName()
        }

        view.findViewById<Button>(R.id.btn_view_gallery).setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_gallery)
        }
        view.findViewById<Button>(R.id.btn_import_patch).setOnClickListener {
            importPatch.launch("application/json")
        }
        view.findViewById<Button>(R.id.btn_import_bundle).setOnClickListener {
            showBundleImportDialog()
        }
        view.findViewById<Button>(R.id.btn_import_detector).setOnClickListener {
            importDetector.launch("*/*")
        }
        view.findViewById<Button>(R.id.btn_import_classifier).setOnClickListener {
            importClassifier.launch("*/*")
        }
        view.findViewById<Button>(R.id.btn_import_labels).setOnClickListener {
            importGallery.launch("application/json")
        }
        view.findViewById<Button>(R.id.btn_restore_gallery).setOnClickListener {
            showRestoreDialog()
        }
        view.findViewById<Button>(R.id.btn_factory_reset).setOnClickListener {
            showFactoryResetDialog()
        }

        refreshDisplay()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun saveRangerName() {
        val name = etRangerName.text.toString().trim()
        if (name != appPreferences.rangerName) {
            appPreferences.rangerName = name
        }
    }

    private fun showBundleImportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Import Update Bundle")
            .setMessage(
                "Select the .zip file containing one or more of:\n\n" +
                "• gallery.json          — individual prototypes + threshold\n" +
                "• backbone.tflite       — embedding backbone (V2 or V3)\n" +
                "• yolo.tflite           — face detector\n\n" +
                "The zip is validated before anything is replaced.\n" +
                "A backup of the current gallery is created automatically."
            )
            .setPositiveButton("Choose ZIP") { _, _ ->
                importBundle.launch("*/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRestoreDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { galleryManager.listBackupsWithDiff() }
            if (entries.isEmpty()) { toast("No gallery backups found."); return@launch }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Gallery history  (${entries.size})")
                .setAdapter(BackupListAdapter(requireContext(), entries)) { _, which ->
                    showRestoreConfirmDialog(entries[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showRestoreConfirmDialog(entry: GalleryManager.BackupEntry) {
        val fmt     = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault())
        val timeStr = fmt.format(Date(entry.timestamp))

        val impact = buildString {
            if (entry.addedAfter.isNotEmpty())
                appendLine("Will remove: ${entry.addedAfter.joinToString(", ")}")
            if (entry.removedAfter.isNotEmpty())
                appendLine("Will restore: ${entry.removedAfter.joinToString(", ")}")
            if (entry.fieldUpdatesAfter.isNotEmpty())
                appendLine("Will undo: ${entry.fieldUpdatesAfter.joinToString(", ")}")
        }.trimEnd()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore to $timeStr?")
            .setMessage(
                if (impact.isBlank()) "Gallery will be restored to this state.\nThe current gallery is backed up first."
                else "$impact\n\nThe current gallery is backed up first."
            )
            .setPositiveButton("Restore") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { modelManager.restoreGallery(entry.file) }
                    refreshDisplay()
                    toast("Gallery restored to $timeStr")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Custom adapter that renders each backup entry with its timestamp, count, and diff. */
    private inner class BackupListAdapter(
        context: Context,
        private val entries: List<GalleryManager.BackupEntry>
    ) : ArrayAdapter<GalleryManager.BackupEntry>(context, 0, entries) {

        private val inflater = LayoutInflater.from(context)
        private val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault())

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view  = convertView ?: inflater.inflate(R.layout.item_gallery_backup, parent, false)
            val entry = entries[position]

            view.findViewById<TextView>(R.id.tv_backup_timestamp).text =
                fmt.format(Date(entry.timestamp))
            view.findViewById<TextView>(R.id.tv_backup_count).text =
                "${entry.individualCount} individuals"
            view.findViewById<TextView>(R.id.tv_backup_changes).text =
                entry.changesSummary()

            return view
        }
    }

    private fun installSingleModel(uri: android.net.Uri, filename: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { FileUtils.readUriToBytes(requireContext(), uri) }
            if (bytes == null) { toast("Could not read file."); return@launch }
            withContext(Dispatchers.IO) { modelManager.installModelFile(bytes, filename) }
            refreshDisplay()
            toast("$filename installed.")
        }
    }

    private fun thresholdLabel(t: Float) =
        "Recognition threshold: %.0f%%  [10%% – 35%%]".format(t * 100)

    private fun refreshDisplay() {
        tvDetectorVersion.text   = "Detector:  ${modelManager.activeDetectorVersion()}"
        tvClassifierVersion.text = "Backbone:  ${modelManager.activeClassifierVersion()}\n" +
                                   "Gallery:   ${modelManager.activeGalleryVersion()}"
        val t = modelManager.getUnknownThreshold().coerceIn(0.10f, 0.35f)
        tvThreshold.text = thresholdLabel(t)
        seekbarThreshold.progress = ((t - 0.10f) * 1000f).toInt()

        val backups = modelManager.listGalleryBackups()
        tvBackupInfo.text = if (backups.isEmpty()) {
            "No backups yet — one is created automatically before each gallery change."
        } else {
            "${backups.size}/20 backups saved — tap Restore to browse history."
        }
    }

    // ── Factory reset ─────────────────────────────────────────────────────────

    private fun showFactoryResetDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Factory reset?")
            .setMessage(
                "This will permanently delete:\n\n" +
                "• All scan history\n" +
                "• All custom gallery changes (added individuals, corrections)\n" +
                "• All imported models\n\n" +
                "The original 10 individuals and base AI models will be restored.\n\n" +
                "This cannot be undone."
            )
            .setPositiveButton("Continue →") { _, _ -> showFactoryResetConfirmation() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFactoryResetConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠  Last warning")
            .setMessage("All your data will be permanently erased. Are you absolutely sure?")
            .setPositiveButton("Reset everything") { _, _ -> performFactoryReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performFactoryReset() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // 1. Clear scan history from Room DB
                runCatching { scanRepository.clearAllScans() }
                // 2. Delete user data directories
                listOf(
                    File(requireContext().filesDir, "models"),
                    File(requireContext().filesDir, "scans"),
                    File(requireContext().filesDir, "exports"),
                    File(requireContext().cacheDir,  "individual_crops"),
                    File(requireContext().cacheDir,  "temp_photos")
                ).forEach { dir -> dir.deleteRecursively() }
                // 3. Reset preferences — commit() is required here because the process
                //    is killed immediately after; apply() writes asynchronously and would be lost
                requireContext().getSharedPreferences("orang_identifier_prefs", Context.MODE_PRIVATE)
                    .edit().clear().commit()
                // 4. Invalidate model cache (models reload from assets on next inference)
                modelManager.invalidate()
            }
            // Restart app cleanly
            val intent = requireActivity().packageManager
                .getLaunchIntentForPackage(requireActivity().packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            if (intent != null) {
                requireActivity().startActivity(intent)
                requireActivity().finish()
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // Uses Fragment.toast() extension from utils/Extensions.kt
}
