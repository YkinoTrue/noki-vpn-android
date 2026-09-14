package com.noki.vpn

import java.io.File

internal object AndroidUpdateCachePolicy {
    fun clearInstalledApks(context: android.content.Context) {
        val version = BuildConfig.VERSION_NAME
        File(context.cacheDir, "android_updates").listFiles()
            ?.filter { shouldDeleteCachedApk(it, version) }
            ?.forEach { it.delete() }
    }

    fun fileName(update: AndroidUpdateInfo): String {
        val safeVersion = update.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeArchitecture = update.architecture.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "Noki Vpn-$safeVersion-$safeArchitecture.apk"
    }

    fun cachedVersionName(fileName: String): String? {
        val payload = fileName
            .removePrefix("Noki Vpn-")
            .takeIf { it != fileName }
            ?.removeSuffix(".apk")
            ?: return null
        val architectureSuffixes = listOf(
            "-arm64-v8a",
            "-armeabi-v7a",
            "-x86_64",
            "-universal",
        )
        return architectureSuffixes.firstNotNullOfOrNull { suffix ->
            payload.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)
        }
    }

    fun shouldDeleteCachedApk(file: File, currentVersionName: String): Boolean {
        val name = file.name.removeSuffix(".part")
        if (!file.isFile || !name.endsWith(".apk", ignoreCase = true)) return false
        val versionName = cachedVersionName(name) ?: return true
        return compareVersionNames(versionName, currentVersionName) <= 0
    }

    fun compareVersionNames(left: String, right: String): Int {
        val leftParts = left.split('.', '-', '_')
        val rightParts = right.split('.', '-', '_')
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftPart = leftParts.getOrNull(index).orEmpty()
            val rightPart = rightParts.getOrNull(index).orEmpty()
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                else -> leftPart.compareTo(rightPart)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }
}
