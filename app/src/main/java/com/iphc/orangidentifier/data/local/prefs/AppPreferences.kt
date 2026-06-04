package com.iphc.orangidentifier.data.local.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("orang_identifier_prefs", Context.MODE_PRIVATE)

    /**
     * Unknown identification threshold.
     * If the top cosine similarity is below this value the result is "Unknown".
     * Overridden by gallery.json's unknown_threshold field on every gallery load.
     * V2 default: ~0.49  |  V3 default: ~0.22
     */
    var unknownThreshold: Float
        get() = prefs.getFloat("unknown_threshold", 0.22f)
        set(value) = prefs.edit { putFloat("unknown_threshold", value) }

    /**
     * Name of the ranger using the device.
     * Used to name patch export files (e.g. "patch_Jean_20260615.json").
     */
    var rangerName: String
        get() = prefs.getString("ranger_name", "") ?: ""
        set(value) = prefs.edit { putString("ranger_name", value.trim()) }
}
