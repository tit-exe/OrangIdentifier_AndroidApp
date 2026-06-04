package com.iphc.orangidentifier.domain.repository

import com.iphc.orangidentifier.domain.model.ScanRecord
import kotlinx.coroutines.flow.Flow

/**
 * Contract for scan history persistence. No Android imports — pure domain code.
 */
interface ScanRepository {
    suspend fun saveScan(record: ScanRecord): Long
    suspend fun updateScan(record: ScanRecord)
    fun getAllScans(): Flow<List<ScanRecord>>
    suspend fun getScanById(id: Long): ScanRecord?
    suspend fun deleteScan(id: Long)
    suspend fun clearAllScans()
}
