package com.iphc.orangidentifier.domain.usecase

import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Exposes the scan history as a Flow from Room.
 */
class GetScanHistoryUseCase @Inject constructor(
    private val scanRepository: ScanRepository
) {
    fun execute(): Flow<List<ScanRecord>> = scanRepository.getAllScans()
}
