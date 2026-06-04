package com.iphc.orangidentifier.di

import android.content.Context
import com.iphc.orangidentifier.data.local.prefs.AppPreferences
import com.iphc.orangidentifier.data.repository.ModelManager
import com.iphc.orangidentifier.data.repository.ScanRepositoryImpl
import com.iphc.orangidentifier.domain.repository.ScanRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindScanRepository(impl: ScanRepositoryImpl): ScanRepository

    companion object {
        @Provides
        @Singleton
        fun provideModelManager(
            @ApplicationContext context: Context,
            prefs: AppPreferences
        ): ModelManager = ModelManager(context, prefs)

        // YoloDetector and MegaDescriptorBackbone use @Inject constructor + @Singleton
        // so Hilt discovers them automatically — no @Provides needed here.
    }
}
