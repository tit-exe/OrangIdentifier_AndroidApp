package com.iphc.orangidentifier.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.domain.usecase.GetScanHistoryUseCase
import com.iphc.orangidentifier.domain.usecase.InferenceResult
import com.iphc.orangidentifier.domain.usecase.RunImageInferenceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val runImageInferenceUseCase: RunImageInferenceUseCase,
    private val getScanHistoryUseCase: GetScanHistoryUseCase
) : ViewModel() {

    val scanHistory: StateFlow<List<ScanRecord>> = getScanHistoryUseCase.execute()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Inference state (triggers initial navigation to ScanResultFragment) ─────

    private val _inferenceState = MutableStateFlow<InferenceState>(InferenceState.Idle)
    val inferenceState: StateFlow<InferenceState> = _inferenceState.asStateFlow()

    // ── Batch session (multi-image scan) ──────────────────────────────────────

    /**
     * Active batch. Null when no batch or a single-image scan.
     *
     * @param total              Number of URIs originally selected (includes skipped ones).
     * @param completedIds       Ordered list of saved scan IDs (only successful ones).
     * @param processedCount     How many URIs have been attempted (for progress display).
     * @param currentViewingIndex Index into [completedIds] the user is currently viewing.
     * @param isProcessingMore   True while the queue is still running.
     */
    data class BatchSession(
        val total: Int,
        val completedIds: List<Long>,
        val processedCount: Int,
        val currentViewingIndex: Int,
        val isProcessingMore: Boolean
    )

    private val _batchSession = MutableStateFlow<BatchSession?>(null)
    val batchSession: StateFlow<BatchSession?> = _batchSession.asStateFlow()

    // Tracks the active inference coroutine so it can be cancelled before starting a new one
    private var inferenceJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Single-image scan — no batch UI. */
    fun processImage(uri: Uri, sourceType: ScanRecord.SourceType) {
        processBatch(listOf(uri), sourceType, showBatchUI = false)
    }

    /**
     * Multi-image scan. Processes URIs sequentially on IO (TFLite is single-threaded).
     * Navigates to the first successful result immediately; subsequent results are queued.
     */
    fun processBatch(uris: List<Uri>, sourceType: ScanRecord.SourceType) {
        processBatch(uris, sourceType, showBatchUI = uris.size > 1)
    }

    /** Advance to the next result in the active batch. */
    fun advanceBatchIndex() {
        val session = _batchSession.value ?: return
        val next = session.currentViewingIndex + 1
        // Allow advance only if the next slot exists OR more results are coming
        // Never advance beyond the last known completed result + 1 (waiting slot)
        val maxAdvanceable = if (session.isProcessingMore) session.completedIds.size
                             else session.completedIds.size - 1
        if (next <= maxAdvanceable) {
            _batchSession.value = session.copy(currentViewingIndex = next)
        }
    }

    /** Clear the active batch (called when user starts a new scan or leaves results). */
    fun clearBatch() {
        _batchSession.value = null
    }

    fun resetInferenceState() {
        _inferenceState.value = InferenceState.Idle
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun processBatch(
        uris: List<Uri>,
        sourceType: ScanRecord.SourceType,
        showBatchUI: Boolean
    ) {
        if (uris.isEmpty()) return

        // Cancel any previous inference before starting a new one.
        // This handles edge cases such as device rotation while analysis is running,
        // or rapid back-and-forth navigation that bypasses the disabled-button guard.
        inferenceJob?.cancel()

        _batchSession.value = if (showBatchUI) {
            BatchSession(
                total = uris.size,
                completedIds = emptyList(),
                processedCount = 0,
                currentViewingIndex = 0,
                isProcessingMore = true
            )
        } else null

        _inferenceState.value = InferenceState.Loading

        inferenceJob = viewModelScope.launch {
            var navigated = false   // have we triggered first navigation?

            for ((idx, uri) in uris.withIndex()) {
                val result = runImageInferenceUseCase.execute(uri, sourceType)

                when (result) {
                    is InferenceResult.Success -> {
                        val current = _batchSession.value
                        val newIds = (current?.completedIds ?: emptyList()) + result.recordId
                        if (showBatchUI) {
                            _batchSession.value = current?.copy(
                                completedIds = newIds,
                                processedCount = idx + 1,
                                isProcessingMore = idx < uris.lastIndex
                            )
                        }
                        if (!navigated) {
                            navigated = true
                            _inferenceState.value = InferenceState.Success(result.recordId)
                        }
                    }
                    is InferenceResult.NoFaceFound, is InferenceResult.Failure -> {
                        // Silently skip; adjust processedCount only
                        if (showBatchUI) {
                            _batchSession.value = _batchSession.value?.copy(
                                processedCount = idx + 1,
                                isProcessingMore = idx < uris.lastIndex
                            )
                        }
                    }
                }
            }

            // Queue exhausted
            if (showBatchUI) {
                _batchSession.value = _batchSession.value?.copy(isProcessingMore = false)
            }

            if (!navigated) {
                _inferenceState.value = InferenceState.Error("No primate faces detected in any of the selected images.")
                _batchSession.value = null
            }
        }
    }

    // ── States ────────────────────────────────────────────────────────────────

    sealed class InferenceState {
        object Idle    : InferenceState()
        object Loading : InferenceState()
        data class Success(val recordId: Long) : InferenceState()
        data class Error(val message: String)  : InferenceState()
    }
}
