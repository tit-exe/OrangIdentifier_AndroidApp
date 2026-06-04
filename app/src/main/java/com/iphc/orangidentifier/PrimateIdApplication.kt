package com.iphc.orangidentifier

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. @HiltAndroidApp triggers Hilt code generation.
 * All singletons (ModelManager, DB, Repository) are created here via Hilt.
 */
@HiltAndroidApp
class PrimateIdApplication : Application()
