package com.noki.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.BackendException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.BackendAndroidUpdate
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal data class AndroidUpdateLogEvent(
    val message: String,
    val details: String? = null,
    val errorType: String? = null,
)

internal fun interface AndroidUpdateStateLoader {
    suspend fun loadStateWithToken(
        token: String,
        fallbackState: AndroidUpdateUiState,
        language: AppLanguage,
    ): AndroidUpdateUiState
}

class AndroidUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = SettingsRepository(applicationContext)
        val settings = repository.load()
        val versionCode = inputData.getLong("version_code", 0)
        if (versionCode <= repository.currentAppVersionCode()) {
            AndroidUpdateCachePolicy.clearInstalledApks(applicationContext)
            return Result.success()
        }
        if (!settings.isAuthenticated || settings.backendDeviceId != inputData.getString("device_id")) {
            return Result.failure()
        }
        val api = BackendApiClient()
        val auth = AuthSessionCoordinator(repository, AuthTokenRefresher(repository, api)).apply { restore(settings) }
        val coordinator = AndroidUpdateCoordinator(applicationContext as Application, repository, api, auth) { event ->
            repository.recordAppLog(category = "android_update", message = event.message,
                details = event.details, errorType = event.errorType)
        }
        val russian = settings.personalizationSettings.language == AppLanguage.RU
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Обновление Noki", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_noki_notification)
            .setContentTitle("Noki — ${inputData.getString("version_name")}")
            .setContentText(if (russian) "Скачивание обновления" else "Downloading update")
            .setOngoing(true).setProgress(0, 0, true)
            .setContentIntent(PendingIntent.getActivity(applicationContext, 234,
                Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        return try {
            setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(DOWNLOAD_NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(DOWNLOAD_NOTIFICATION, notification))
            val update = AndroidUpdateInfo(versionCode, requireNotNull(inputData.getString("version_name")),
                architecture = requireNotNull(inputData.getString("architecture")),
                apkUrl = requireNotNull(inputData.getString("url")), apkSha256 = inputData.getString("sha256"),
                apkSizeBytes = inputData.getLong("size", 0).takeIf { it > 0 })
            val file = coordinator.download(update) {
                val latest = repository.load()
                check(latest.isAuthenticated && latest.backendDeviceId == settings.backendDeviceId) { "auth_required" }
            }
            val uri = FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.fileprovider", file)
            val installIntent = PendingIntent.getActivity(applicationContext, 235,
                coordinator.buildInstallerIntent(uri), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
                    applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                manager.notify(READY_NOTIFICATION, NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_noki_notification)
                .setContentTitle(if (russian) "Обновление Noki скачано" else "Noki update downloaded")
                .setContentText(if (russian) "Нажмите, чтобы установить ${update.versionName}" else "Tap to install ${update.versionName}")
                    .setContentIntent(installIntent).setAutoCancel(true).build())
            }
            Result.success(workDataOf("version_code" to versionCode, "filename" to file.name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < 5 && (error is IOException || error is BackendException && error.statusCode in 500..599)) {
                Result.retry()
            } else {
                Result.failure(workDataOf("version_code" to versionCode,
                    "error" to coordinator.readableInstallError(settings.personalizationSettings.language, error)))
            }
        }
    }

    companion object {
        internal const val WORK_NAME = "user_requested_android_update"
        private const val CHANNEL = "noki_android_update"
        private const val DOWNLOAD_NOTIFICATION = 52_101
        internal const val READY_NOTIFICATION = 52_102

        internal fun enqueue(context: Context, update: AndroidUpdateInfo, deviceId: String) {
            val request = OneTimeWorkRequestBuilder<AndroidUpdateWorker>()
                .setInputData(workDataOf("version_code" to update.versionCode, "version_name" to update.versionName,
                    "architecture" to update.architecture, "url" to update.apkUrl, "sha256" to update.apkSha256,
                    "size" to (update.apkSizeBytes ?: 0), "device_id" to deviceId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

class AndroidUpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AndroidUpdateCachePolicy.clearInstalledApks(context)
        context.getSystemService(NotificationManager::class.java).cancel(AndroidUpdateWorker.READY_NOTIFICATION)
    }
}

internal class AndroidUpdateCoordinator(
    private val app: Application,
    private val repository: SettingsRepository,
    private val backendApi: BackendApiClient,
    private val authRunner: AuthenticatedCallRunner,
    private val logEvent: (AndroidUpdateLogEvent) -> Unit,
) : AndroidUpdateStateLoader {
    fun unauthenticatedState(): AndroidUpdateUiState {
        repository.clearAndroidUpdateAvailable()
        return AndroidUpdateUiState(currentVersionName = repository.currentAppVersionName())
    }

    suspend fun loadState(
        fallbackState: AndroidUpdateUiState,
        language: AppLanguage,
    ): AndroidUpdateUiState {
        return try {
            authRunner.run { token -> loadStateWithToken(token, fallbackState, language) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fallbackState.copy(
                isChecking = false,
                currentVersionName = repository.currentAppVersionName(),
                error = AppErrorMapper.readableNetworkError(language, error),
            )
        }
    }

    fun requestInstallPermissionIfNeeded(language: AppLanguage): String? {
        if (app.packageManager.canRequestPackageInstalls()) {
            return null
        }
        runCatching {
            app.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${app.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return tr(
            language,
            "Разрешите установку из этого источника и нажмите обновление ещё раз",
            "Allow installs from this source and tap update again",
        )
    }

    suspend fun download(
        update: AndroidUpdateInfo,
        ensureDownloadCurrent: () -> Unit,
    ): File {
        val targetFile = androidUpdateFile(update)
        clearSameOrOlderCachedApks()
        targetFile.delete()
        targetFile.parentFile?.mkdirs()

        logEvent(
            AndroidUpdateLogEvent(
                message = "download_start",
                details = "version=${update.versionName}, architecture=${update.architecture}, size=${update.apkSizeBytes ?: 0}",
            ),
        )

        try {
            authRunner.run { token ->
                backendApi.downloadAndroidUpdateApk(
                    token = token,
                    apkUrl = update.apkUrl,
                    expectedSha256 = update.apkSha256,
                    expectedSizeBytes = update.apkSizeBytes,
                    destination = targetFile,
                )
            }
            if (targetFile.length() <= 0L) {
                throw IllegalStateException("Downloaded APK is empty")
            }
            logEvent(
                AndroidUpdateLogEvent(
                    message = "download_complete",
                    details = "version=${update.versionName}, architecture=${update.architecture}, bytes=${targetFile.length()}",
                ),
            )
            currentCoroutineContext().ensureActive()
            ensureDownloadCurrent()
            return targetFile
        } catch (cancelled: CancellationException) {
            targetFile.delete()
            clearSameOrOlderCachedApks()
            throw cancelled
        } catch (error: Throwable) {
            targetFile.delete()
            clearSameOrOlderCachedApks()
            logEvent(
                AndroidUpdateLogEvent(
                    message = "download_failed",
                    details = "version=${update.versionName}, architecture=${update.architecture}",
                    errorType = error::class.java.simpleName,
                ),
            )
            throw error
        }
    }

    fun readableInstallError(
        language: AppLanguage,
        error: Throwable,
    ): String {
        val causedByMissingInstaller = generateSequence(error as Throwable?) { it.cause }
            .any { it is ActivityNotFoundException }
        return when {
            causedByMissingInstaller ->
                tr(language, "Не удалось открыть установщик APK", "Failed to open APK installer")

            error is IllegalStateException && error.message == "Downloaded APK is empty" ->
                tr(language, "Скачанный APK пустой", "Downloaded APK is empty")

            error is IllegalStateException && error.message?.contains("APK installer", ignoreCase = true) == true ->
                tr(language, "Не удалось открыть установщик APK", "Failed to open APK installer")

            else -> AppErrorMapper.readableNetworkError(language, error)
        }
    }

    fun clearSameOrOlderCachedApks() {
        AndroidUpdateCachePolicy.clearInstalledApks(app)
    }

    override suspend fun loadStateWithToken(
        token: String,
        fallbackState: AndroidUpdateUiState,
        language: AppLanguage,
    ): AndroidUpdateUiState {
        val currentVersionCode = repository.currentAppVersionCode()
        val currentVersionName = repository.currentAppVersionName()
        val update = try {
            backendApi.androidUpdate(
                token = token,
                versionCode = currentVersionCode,
                abis = Build.SUPPORTED_ABIS.toList(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return fallbackState.copy(
                isChecking = false,
                currentVersionName = currentVersionName,
                error = if (fallbackState.update == null) {
                    AppErrorMapper.readableNetworkError(language, error)
                } else {
                    fallbackState.error
                },
            )
        }

        currentCoroutineContext().ensureActive()
        val uiUpdate = update.toAndroidUpdateInfoOrNull(currentVersionCode)
        if (uiUpdate == null) {
            repository.clearAndroidUpdateAvailable()
        } else {
            repository.markAndroidUpdateAvailable()
        }
        val download = observeDownloads().first()
        return AndroidUpdateUiState(
            currentVersionName = currentVersionName,
            update = uiUpdate,
            isDownloading = download != null && !download.state.isFinished,
            isReadyToInstall = readyFile(download) != null,
            error = download?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error"),
        )
    }

    private fun BackendAndroidUpdate.toAndroidUpdateInfoOrNull(currentVersionCode: Long): AndroidUpdateInfo? {
        if (!updateAvailable) return null
        val code = versionCode ?: return null
        if (code <= currentVersionCode) return null
        val url = apkUrl?.takeIf { it.isNotBlank() } ?: return null
        return AndroidUpdateInfo(
            versionCode = code,
            versionName = versionName?.takeIf { it.isNotBlank() } ?: code.toString(),
            releaseNotes = releaseNotes,
            isForced = isForced,
            architecture = architecture?.takeIf { it.isNotBlank() } ?: "universal",
            apkUrl = url,
            apkSha256 = apkSha256,
            apkSizeBytes = apkSizeBytes,
        )
    }

    internal fun observeDownloads() = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(AndroidUpdateWorker.WORK_NAME)
        .map { items -> items.firstOrNull { !it.state.isFinished } ?: items.maxByOrNull { it.outputData.getLong("version_code", 0) } }

    internal fun readyFile(work: WorkInfo?): File? {
        if (work?.state != WorkInfo.State.SUCCEEDED) return null
        if (work.outputData.getLong("version_code", 0) <= repository.currentAppVersionCode()) return null
        val name = work.outputData.getString("filename") ?: return null
        if (name != File(name).name) return null
        return File(androidUpdateDirectory(), name).takeIf { it.isFile && it.length() > 0 }
    }

    internal suspend fun launchReadyUpdate(explicit: Boolean): Boolean {
        val work = observeDownloads().first() ?: return false
        val file = readyFile(work) ?: return false
        val preferences = app.getSharedPreferences("android_update_install", Context.MODE_PRIVATE)
        if (!explicit && preferences.getString("launched", null) == work.id.toString()) return false
        if (!app.packageManager.canRequestPackageInstalls()) return false
        try {
            launchInstaller(file)
            preferences.edit().putString("launched", work.id.toString()).apply()
            return true
        } catch (error: Exception) {
            logEvent(AndroidUpdateLogEvent("installer_launch_failed", errorType = error.javaClass.simpleName))
            return false
        }
    }

    private fun androidUpdateFile(update: AndroidUpdateInfo): File {
        return File(androidUpdateDirectory(), AndroidUpdateCachePolicy.fileName(update))
    }

    private fun androidUpdateDirectory(): File {
        return File(app.cacheDir, "android_updates")
    }

    internal fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            file,
        )
        val intent = buildInstallerIntent(uri)
        try {
            logEvent(
                AndroidUpdateLogEvent(
                    message = "installer_launch",
                    details = "bytes=${file.length()}",
                ),
            )
            app.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("APK installer is not available", error)
        }
    }

    internal fun buildInstallerIntent(uri: Uri): Intent {
        val candidates = listOf(
            Intent(Intent.ACTION_INSTALL_PACKAGE),
            Intent(Intent.ACTION_VIEW),
        ).map { intent ->
            intent.setClipData(ClipData.newUri(app.contentResolver, "Noki Android update", uri))
            intent
                .setDataAndType(uri, APK_MIME_TYPE)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return candidates.firstOrNull { intent ->
            app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        } ?: candidates.first()
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
