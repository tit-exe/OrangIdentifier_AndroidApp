package com.iphc.orangidentifier.di

import android.content.Context
import androidx.room.Room
import com.iphc.orangidentifier.data.local.db.AppDatabase
import com.iphc.orangidentifier.data.local.db.ScanDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "primate_id_database"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun provideScanDao(database: AppDatabase): ScanDao {
        return database.scanDao()
    }
}
