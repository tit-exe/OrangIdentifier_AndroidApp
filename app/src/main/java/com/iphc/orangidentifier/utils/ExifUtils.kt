package com.iphc.orangidentifier.utils

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Reads EXIF orientation from a content URI.
 */
object ExifUtils {

    fun getRotationDegrees(context: Context, uri: Uri): Int {
        return try {
            val stream: InputStream = context.contentResolver.openInputStream(uri) ?: return 0
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            stream.close()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
