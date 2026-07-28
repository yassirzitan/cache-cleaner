package com.example.cachecleaner

import android.graphics.drawable.Drawable

data class AppCacheInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val cacheBytes: Long,
    var visited: Boolean = false
) {
    fun formattedSize(): String {
        val kb = cacheBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "$cacheBytes B"
        }
    }
}
