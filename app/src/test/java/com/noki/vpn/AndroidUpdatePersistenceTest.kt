package com.noki.vpn

import android.app.Application
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUpdatePersistenceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app,
            Configuration.Builder().setExecutor { it.run() }.build())
    }

    @Test fun closingTheUiObserverKeepsOnePersistentDownloadQueued() = runBlocking {
        val manager = WorkManager.getInstance(app)
        val update = update()
        AndroidUpdateWorker.enqueue(app, update, "device")
        val before = manager.getWorkInfosForUniqueWork(AndroidUpdateWorker.WORK_NAME).get(5, TimeUnit.SECONDS).single()
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        uiScope.launch { manager.getWorkInfosForUniqueWorkFlow(AndroidUpdateWorker.WORK_NAME).collect() }
        uiScope.cancel()
        AndroidUpdateWorker.enqueue(app, update, "device")
        val restored = manager.getWorkInfosForUniqueWork(AndroidUpdateWorker.WORK_NAME).get(5, TimeUnit.SECONDS).single()
        assertEquals(before.id, restored.id)
        assertEquals(WorkInfo.State.ENQUEUED, restored.state)
        manager.cancelUniqueWork(AndroidUpdateWorker.WORK_NAME).result.get(5, TimeUnit.SECONDS)
        Unit
    }

    @Test fun downloadCompletesWithoutOpeningInstallerAndRemainsReadyAcrossCoordinatorRecreation() = runBlocking {
        val bytes = "verified apk payload".toByteArray()
        val api = BackendApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build(), "https://updates.example")
        val coordinator = coordinator(api)
        val file = coordinator.download(update().copy(apkSizeBytes = bytes.size.toLong())) {}
        assertArrayEquals(bytes, file.readBytes())
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        val work = WorkInfo(java.util.UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet(),
            outputData = androidx.work.workDataOf("version_code" to update().versionCode, "filename" to file.name))
        assertEquals(file, coordinator(api).readyFile(work))
        assertTrue(file.delete())
    }

    @Test fun packageReplacementDeletesInstalledApksAndPartsButKeepsANewerDownload() {
        val directory = File(app.cacheDir, "android_updates").apply { mkdirs() }
        val installed = File(directory, "Noki Vpn-${BuildConfig.VERSION_NAME}-arm64-v8a.apk").apply { writeText("apk") }
        val partial = File(directory, "Noki Vpn-0.1-arm64-v8a.apk.part").apply { writeText("part") }
        val newer = File(directory, "Noki Vpn-9999.0-arm64-v8a.apk.part").apply { writeText("new") }
        AndroidUpdateInstalledReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(installed.exists())
        assertFalse(partial.exists())
        assertTrue(newer.exists())
        assertTrue(newer.delete())
    }

    private fun coordinator(api: BackendApiClient) = AndroidUpdateCoordinator(app, SettingsRepository(app), api,
        object : AuthenticatedCallRunner {
            override suspend fun <T> run(block: suspend (String) -> T): T = block("test-token")
        }, {})

    private fun update() = AndroidUpdateInfo(BuildConfig.VERSION_CODE.toLong() + 1, "9999.0",
        architecture = "arm64-v8a", apkUrl = "/v1/app/android-releases/test.apk")
}
