package com.iphc.orangidentifier.utils

import android.content.Context
import android.net.Uri
import java.io.File

object FileUtils {

    /**
     * Reads the entire contents of a content URI into a ByteArray.
     * Used for installing user-provided model files.
     */
    fun readUriToBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the internal directory used for storing scans.
     */
    fun getScansDir(context: Context): File {
        return File(context.filesDir, "scans").also { if (!it.exists()) it.mkdirs() }
    }

    /**
     * Returns the internal directory used for user-provided models.
     */
    fun getModelsDir(context: Context): File {
        return File(context.filesDir, "models").also { if (!it.exists()) it.mkdirs() }
    }
}
