package com.iphc.orangidentifier.utils

import android.widget.Toast
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

/** Show a short toast from a Fragment. */
fun Fragment.toast(message: String) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
}

/** Format a timestamp millis to a readable date/time string. */
fun Long.toFormattedDateTime(): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault())
    return sdf.format(Date(this))
}

