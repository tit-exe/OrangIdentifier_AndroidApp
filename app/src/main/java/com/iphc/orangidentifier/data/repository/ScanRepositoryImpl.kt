package com.iphc.orangidentifier.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.iphc.orangidentifier.data.local.db.ScanDao
import com.iphc.orangidentifier.data.local.db.ScanEntity
import com.iphc.orangidentifier.domain.model.Detection
import com.iphc.orangidentifier.domain.model.ScanRecord
import com.iphc.orangidentifier.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepositoryImpl @Inject constructor(
    private val scanDao: ScanDao
) : ScanRepository {

    private val gson = Gson()

    override suspend fun saveScan(record: ScanRecord): Long {
        return scanDao.insertScan(record.toEntity())
    }

    override suspend fun updateScan(record: ScanRecord) {
        scanDao.updateScan(record.toEntity())
    }

    private fun ScanRecord.toEntity() = ScanEntity(
        id                = id,
        timestamp         = timestamp,
        imagePath         = imagePath,
        originalImagePath = originalImagePath,
        sourceType        = sourceType.name,
        detectionsJson    = gson.toJson(detections),
        modelVersion      = modelVersion,
        durationMs        = durationMs,
        manuallyAnnotated = manuallyAnnotated
    )

    override fun getAllScans(): Flow<List<ScanRecord>> {
        return scanDao.getAllScans().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getScanById(id: Long): ScanRecord? {
        return scanDao.getScanById(id)?.toDomain()
    }

    override suspend fun deleteScan(id: Long) {
        scanDao.deleteScan(id)
    }

    override suspend fun clearAllScans() {
        scanDao.clearAll()
    }

    private fun ScanEntity.toDomain(): ScanRecord {
        val type = object : TypeToken<List<Detection>>() {}.type
        val detections: List<Detection> = gson.fromJson(detectionsJson, type) ?: emptyList()
        return ScanRecord(
            id                = id,
            timestamp         = timestamp,
            imagePath         = imagePath,
            originalImagePath = originalImagePath,
            sourceType        = runCatching { ScanRecord.SourceType.valueOf(sourceType) }
                                    .getOrDefault(ScanRecord.SourceType.GALLERY),
            detections        = detections,
            modelVersion      = modelVersion,
            durationMs        = durationMs,
            manuallyAnnotated = manuallyAnnotated
        )
    }
}
