package com.noki.vpn.data

import android.os.Build
import java.util.Locale

object AndroidDeviceInfo {
    private val modelNames: Map<String, String> by lazy {
        AndroidDeviceInfo::class.java.getResourceAsStream("/android-device-names.tsv")
            ?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                lines.filterNot { it.startsWith("#") }.mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
            }.orEmpty()
    }

    fun displayName(rawName: String): String =
        modelNames[rawName.trim().lowercase(Locale.ROOT)] ?: rawName.trim()

    fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }
    }
}
