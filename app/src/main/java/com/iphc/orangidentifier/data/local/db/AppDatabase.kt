package com.iphc.orangidentifier.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ScanEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
}
