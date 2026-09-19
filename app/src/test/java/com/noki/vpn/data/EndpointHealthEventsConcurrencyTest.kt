package com.noki.vpn.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointHealthEventsConcurrencyTest {
    @Test
    fun `waiting flush rechecks consent and auth session after acquiring mutex`() = runBlocking {
        for (changeAccount in listOf(false, true)) {
            val store = InMemoryEndpointHealthEventStore()
            val original = settings("old-token")
            var current = original
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var uploads = 0
            val reporter = EndpointHealthEventReporter(
                store, upload = { _, _ -> uploads++; entered.complete(Unit); release.await() },
                loadSettings = { current },
            )
            store.saveEndpointHealthEventQueue(listOf(event("old")))
            val first = async { reporter.flush(original) }
            entered.await()
            val waiting = async { reporter.flush(original) }
            yield()
            current = if (changeAccount) settings("new-token") else original.copy(
                advancedSettings = original.advancedSettings.copy(anonymousLogsEnabled = false),
            )
            store.saveEndpointHealthEventQueue(listOf(event("replacement")))
            release.complete(Unit)
            first.await()
            waiting.await()
            reporter.recordEvent(original, event("stale"))
            assertEquals(1, uploads)
            assertEquals(if (changeAccount) listOf(event("replacement")) else emptyList(), store.loadEndpointHealthEventQueue())
        }
    }

    @Test
    fun `local logging without automatic consent never uploads or queues telemetry`() = runBlocking {
        val store = InMemoryEndpointHealthEventStore()
        val enabled = settings("token")
        val disabled = enabled.copy(advancedSettings = enabled.advancedSettings.copy(anonymousLogsEnabled = false))
        val reporter = EndpointHealthEventReporter(store, upload = { _, _ -> error("upload without consent") })
        store.saveEndpointHealthEventQueue(listOf(event("persisted")))
        reporter.flush(disabled)
        reporter.recordEvent(disabled, event("new"))
        reporter.recordHeartbeatIfDue(disabled, "heartbeat", EndpointRankingPolicy.NetworkKind.WIFI, null)
        assertEquals(emptyList<EndpointHealthEvent>(), store.loadEndpointHealthEventQueue())
        assertTrue(AppDiagnosticLogPolicy.shouldStoreAppLog(disabled.advancedSettings))
    }

    @Test
    fun `automatic upload preference alone does not enable health logging`() {
        val automaticOnly = AdvancedSettings(
            connectionLogsEnabled = false,
            errorLogsEnabled = false,
            anonymousLogsEnabled = true,
        )

        assertFalse(AppDiagnosticLogPolicy.shouldUploadAutomatically(automaticOnly))
        assertTrue(
            AppDiagnosticLogPolicy.shouldUploadAutomatically(
                automaticOnly.copy(connectionLogsEnabled = true),
            ),
        )
    }

    @Test
    fun `concurrent flushes do not upload the same identical batch`() = runBlocking {
        val store = InMemoryEndpointHealthEventStore()
        val firstUploadStarted = CompletableDeferred<Unit>()
        val resumeFirstUpload = CompletableDeferred<Unit>()
        val secondUploadStarted = CompletableDeferred<Unit>()
        val batch = List(EndpointHealthEvents.MAX_BATCH_SIZE) { event("same") }
        val settings = settings(token = "token")
        val firstReporter = EndpointHealthEventReporter(
            store = store,
            upload = { _, events ->
                assertEquals(batch, events)
                firstUploadStarted.complete(Unit)
                resumeFirstUpload.await()
            },
        )
        val secondReporter = EndpointHealthEventReporter(
            store = store,
            upload = { _, events ->
                assertEquals(batch, events)
                secondUploadStarted.complete(Unit)
            },
        )

        store.saveEndpointHealthEventQueue(batch + batch)
        val firstFlush = async { firstReporter.flush(settings) }
        firstUploadStarted.await()
        val secondFlush = async { secondReporter.flush(settings) }
        yield()

        assertFalse("second flush must wait for the first acknowledgement", secondUploadStarted.isCompleted)
        resumeFirstUpload.complete(Unit)
        firstFlush.await()
        secondFlush.await()

        assertTrue(secondUploadStarted.isCompleted)
        assertEquals(emptyList<EndpointHealthEvent>(), store.loadEndpointHealthEventQueue())
    }

    @Test
    fun `successful flush retains event appended during upload`() = runBlocking {
        val store = InMemoryEndpointHealthEventStore()
        val uploadStarted = CompletableDeferred<Unit>()
        val resumeUpload = CompletableDeferred<Unit>()
        val first = event("first")
        val second = event("second")
        val appendApplied = CompletableDeferred<Unit>()
        store.onQueueUpdated = { queue ->
            if (queue == listOf(first, second)) appendApplied.complete(Unit)
        }
        val flushReporter = EndpointHealthEventReporter(
            store = store,
            upload = { _, events ->
                assertEquals(listOf(first), events)
                uploadStarted.complete(Unit)
                resumeUpload.await()
            },
        )
        val appendReporter = EndpointHealthEventReporter(store = store, upload = { _, _ -> })
        val uploadSettings = settings(token = "token")

        store.saveEndpointHealthEventQueue(listOf(first))
        val flush = async { flushReporter.flush(uploadSettings) }
        uploadStarted.await()
        val append = async { appendReporter.recordEvent(settings(token = null), second) }
        appendApplied.await()
        resumeUpload.complete(Unit)
        flush.await()
        append.await()

        assertEquals(listOf(second), store.loadEndpointHealthEventQueue())
    }

    private fun settings(token: String?): StoredSettings =
        DefaultStoredSettingsFactory.create().copy(backendAccessToken = token)

    private fun event(code: String) = EndpointHealthEvent(
        endpointCode = code,
        networkKind = EndpointRankingPolicy.NetworkKind.WIFI,
        eventType = EndpointHealthEventType.ACTIVE_PROBE_SUCCESS,
        success = true,
        slow = false,
        scoreBucket = EndpointScoreBucket.GOOD,
    )
}

private class InMemoryEndpointHealthEventStore : EndpointHealthEventStore {
    private val lock = Any()
    private var queue = emptyList<EndpointHealthEvent>()
    private var lastHeartbeatAtMillis = 0L
    var onQueueUpdated: ((List<EndpointHealthEvent>) -> Unit)? = null

    override fun loadEndpointHealthEventQueue(): List<EndpointHealthEvent> = synchronized(lock) { queue }

    override fun saveEndpointHealthEventQueue(events: List<EndpointHealthEvent>) {
        val updated = synchronized(lock) {
            queue = events
            queue
        }
        onQueueUpdated?.invoke(updated)
    }

    override fun updateEndpointHealthEventQueue(
        transform: (List<EndpointHealthEvent>) -> List<EndpointHealthEvent>,
    ) {
        val updated = synchronized(lock) {
            queue = transform(queue)
            queue
        }
        onQueueUpdated?.invoke(updated)
    }

    override fun loadEndpointHealthLastHeartbeatAtMillis(): Long = synchronized(lock) { lastHeartbeatAtMillis }

    override fun saveEndpointHealthLastHeartbeatAtMillis(value: Long) {
        synchronized(lock) {
            lastHeartbeatAtMillis = value
        }
    }
}
