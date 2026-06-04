package com.iphc.orangidentifier.ui.box_editor

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iphc.orangidentifier.domain.repository.ScanRepository
import com.iphc.orangidentifier.domain.usecase.RunImageInferenceUseCase
import com.iphc.orangidentifier.utils.BitmapUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BoxEditorViewModel @Inject constructor(
    private val scanRepository: ScanRepository,
    private val inferenceUseCase: RunImageInferenceUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed class UiState {
        object Editing   : UiState()
        object Analyzing : UiState()
        data class Done(val scanId: Long)     : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Editing)
    val state: StateFlow<UiState> = _state

    private var originalImagePath = ""

    fun loadScan(scanId: Long) {
        viewModelScope.launch {
            val scan = scanRepository.getScanById(scanId) ?: return@launch
            // Prefer stored original; fall back to annotated image
            originalImagePath = scan.originalImagePath.ifEmpty { scan.imagePath }
        }
    }

    fun analyze(scanId: Long, boxes: List<RectF>) {
        if (boxes.isEmpty()) { _state.value = UiState.Error("Add at least one box"); return }
        if (originalImagePath.isEmpty()) { _state.value = UiState.Error("Image not loaded yet"); return }

        _state.value = UiState.Analyzing
        viewModelScope.launch {
            try {
                val detections = withContext(Dispatchers.Default) {
                    inferenceUseCase.runOnCrops(originalImagePath, boxes)
                }
                val existing = scanRepository.getScanById(scanId)
                    ?: throw IllegalStateException("Scan not found")

                val annotatedPath = withContext(Dispatchers.IO) {
                    val bmp = BitmapFactory.decodeFile(originalImagePath)
                        ?: throw IllegalStateException("Cannot decode image")
                    val path = BitmapUtils.saveAnnotatedBitmap(context, bmp, detections)
                    if (!bmp.isRecycled) bmp.recycle()
                    path
                }

                scanRepository.updateScan(
                    existing.copy(
                        imagePath         = annotatedPath,
                        detections        = detections,
                        manuallyAnnotated = true
                    )
                )
                _state.value = UiState.Done(scanId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BoxEditorVM", "Analysis failed", e)
                _state.value = UiState.Error(e.message ?: "Analysis failed")
            }
        }
    }

    fun resetState() { _state.value = UiState.Editing }
}
