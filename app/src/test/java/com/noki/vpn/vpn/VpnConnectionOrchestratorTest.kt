package com.noki.vpn.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnConnectionOrchestratorTest {
    @Test
    fun `independent lifecycle operations wait and cancellation preserves the holder`() = runBlocking {
        val store = InMemorySettingsStore()
        val orchestrator = VpnConnectionOrchestrator(
            RecordingXrayRuntime(mutableListOf()), UnusedTunInterfaceFactory,
            unusedPreparer(store), VpnSettingsCommitCoordinator(store),
            RecordingConnectedSidecars(mutableListOf()),
        )
        val release = CompletableDeferred<Unit>()
        val holder = async { orchestrator.withLifecycleLock { release.await() } }
        yield()
        val cancelled = async { orchestrator.withLifecycleLock { error("cancelled waiter entered") } }
        val waiter = async { orchestrator.withLifecycleLock { 42 } }
        yield()
        assertFalse(waiter.isCompleted)
        cancelled.cancel()
        cancelled.join()
        assertEquals(true, orchestrator.lifecycleMutex.isLocked)
        release.complete(Unit)
        holder.await()
        assertEquals(42, waiter.await())
        assertFalse(orchestrator.lifecycleMutex.isLocked)
    }

    @Test
    fun `invalidated lifecycle cannot deliver an owned completion`() = runBlocking {
        val store = InMemorySettingsStore()
        val orchestrator = VpnConnectionOrchestrator(
            xray = RecordingXrayRuntime(mutableListOf()),
            tunFactory = UnusedTunInterfaceFactory,
            preparer = unusedPreparer(store),
            settings = VpnSettingsCommitCoordinator(store),
            sidecars = RecordingConnectedSidecars(mutableListOf()),
        )
        val finishCleanup = CompletableDeferred<Unit>()
        var completions = 0
        val stop = orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onOwnedCompletion = { completions += 1 },
            onError = { _, error -> throw error },
        ) { finishCleanup.await() }
        yield()

        orchestrator.invalidate()
        finishCleanup.complete(Unit)
        stop.join()

        assertEquals("destroyed lifecycle must not resume a queued START", 0, completions)
        assertEquals(VpnTransitionSnapshot(false, null), orchestrator.transitionSnapshot())
    }

    @Test
    fun `concurrent replacement keeps the newest transition registered`() = runBlocking {
        val firstCancellationEntered = CountDownLatch(1)
        val releaseFirstCancellation = CountDownLatch(1)
        val runtime = BlockingFirstCancellationXrayRuntime(
            entered = firstCancellationEntered,
            release = releaseFirstCancellation,
        )
        val store = InMemorySettingsStore()
        val orchestrator = VpnConnectionOrchestrator(
            xray = runtime,
            tunFactory = UnusedTunInterfaceFactory,
            preparer = unusedPreparer(store),
            settings = VpnSettingsCommitCoordinator(store),
            sidecars = RecordingConnectedSidecars(mutableListOf()),
        )
        val seed = orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.CONNECTION,
            onError = { _, _ -> error("unexpected") },
        ) { awaitCancellation() }
        yield()
        val first = AtomicReference<kotlinx.coroutines.Job>()
        val second = AtomicReference<kotlinx.coroutines.Job>()
        val inspected = AtomicReference<kotlinx.coroutines.Job>()
        val secondLaunchStarted = CountDownLatch(1)
        var inspectorThread: Thread? = null
        var secondThread: Thread? = null
        val firstThread = thread(name = "first-transition") {
            first.set(
                orchestrator.launchTransition(
                    scope = this,
                    operation = VpnConnectionOperation.RESTART,
                    onError = { _, _ -> error("unexpected") },
                ) { awaitCancellation() },
            )
        }
        try {
            assertEquals(true, firstCancellationEntered.await(1L, TimeUnit.SECONDS))
            val inspectionCompleted = CountDownLatch(1)
            inspectorThread = thread(name = "transition-inspector") {
                inspected.set(orchestrator.activeTransitionJob())
                inspectionCompleted.countDown()
            }
            assertEquals(
                "native cancellation must not hold the transition state lock",
                true,
                inspectionCompleted.await(1L, TimeUnit.SECONDS),
            )
            assertSame(seed, inspected.get())
            val startedSecondThread = thread(name = "second-transition") {
                secondLaunchStarted.countDown()
                second.set(
                    orchestrator.launchTransition(
                        scope = this,
                        operation = VpnConnectionOperation.STOP,
                        onError = { _, _ -> error("unexpected") },
                    ) { awaitCancellation() },
                )
            }
            secondThread = startedSecondThread
            assertEquals(true, secondLaunchStarted.await(1L, TimeUnit.SECONDS))
            releaseFirstCancellation.countDown()
            startedSecondThread.join(1_000L)
            assertFalse("newest transition launch must complete", startedSecondThread.isAlive)
            firstThread.join(1_000L)
            assertFalse("older transition launch must complete", firstThread.isAlive)

            assertSame(
                "the highest generation must remain the registered lifecycle owner",
                second.get(),
                orchestrator.activeTransitionJob(),
            )
        } finally {
            releaseFirstCancellation.countDown()
            first.get()?.cancel()
            second.get()?.cancel()
            seed.cancel()
            inspectorThread?.join(1_000L)
            secondThread?.join(1_000L)
            firstThread.join(1_000L)
        }
    }

    @Test
    fun `replacement cancels native readiness before waiting for previous transition`() = runBlocking {
        val events = mutableListOf<String>()
        val store = InMemorySettingsStore()
        val orchestrator = VpnConnectionOrchestrator(
            xray = RecordingXrayRuntime(events),
            tunFactory = UnusedTunInterfaceFactory,
            preparer = unusedPreparer(store),
            settings = VpnSettingsCommitCoordinator(store),
            sidecars = RecordingConnectedSidecars(events),
        )
        val first = orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.CONNECTION,
            onError = { _, _ -> error("unexpected") },
        ) {
            try {
                events += "first_started"
                awaitCancellation()
            } finally {
                events += "first_cancelled"
            }
        }
        yield()

        val second = orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.RESTART,
            awaitPrevious = true,
            onError = { _, _ -> error("unexpected") },
        ) {
            events += "second_started"
        }
        second.join()
        first.join()

        assertEquals(
            listOf("first_started", "cancel_probe", "first_cancelled", "second_started"),
            events,
        )
    }

    @Test
    fun `replacement transition cancels the previous lifecycle owner`() = runBlocking {
        val events = mutableListOf<String>()
        val store = InMemorySettingsStore()
        val orchestrator = VpnConnectionOrchestrator(
            xray = RecordingXrayRuntime(events),
            tunFactory = UnusedTunInterfaceFactory,
            preparer = unusedPreparer(store),
            settings = VpnSettingsCommitCoordinator(store),
            sidecars = RecordingConnectedSidecars(events),
        )
        val first = orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.CONNECTION,
            onError = { _, _ -> error("unexpected") },
        ) {
            try {
                events += "first_started"
                awaitCancellation()
            } finally {
                events += "first_cancelled"
            }
        }
        yield()

        val second = orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.RESTART,
            onError = { _, _ -> error("unexpected") },
        ) {
            events += "second_started"
        }
        second.join()
        first.join()

        assertEquals(
            listOf("first_started", "cancel_probe", "first_cancelled", "second_started"),
            events,
        )
        assertEquals(VpnTransitionSnapshot(false, null), orchestrator.transitionSnapshot())
    }

    @Test
    fun `connected activation and release have one lifecycle resource owner`() = runBlocking {
        val events = mutableListOf<String>()
        val runtime = RecordingXrayRuntime(events)
        val tun = RecordingTunHandle(events)
        val store = InMemorySettingsStore()
        val sidecars = RecordingConnectedSidecars(events)
        val orchestrator = VpnConnectionOrchestrator(
            xray = runtime,
            tunFactory = UnusedTunInterfaceFactory,
            preparer = unusedPreparer(store),
            settings = VpnSettingsCommitCoordinator(store),
            sidecars = sidecars,
        )
        val settings = store.load()
        val underlay = UnderlyingNetworkSnapshot(
            kind = EndpointRankingPolicy.NetworkKind.WIFI,
            signature = "wifi:test",
            vpnShouldBeMetered = false,
            details = "test",
        )

        val generationId = orchestrator.beginTransition()
        orchestrator.withLifecycleLock {
            orchestrator.activateConnected(
                generationId = generationId,
                coreId = 11L,
                tunnel = tun,
                settings = settings,
                underlay = underlay,
            )
        }

        assertEquals(VpnConnectionState.CONNECTED, orchestrator.snapshot().state)
        assertEquals("wifi:test", orchestrator.snapshot().underlay?.signature)
        assertEquals(listOf("start_sidecars:11"), events)

        orchestrator.releaseOwnedResources(VpnConnectionState.DISCONNECTED)

        assertEquals(
            listOf(
                "start_sidecars:11",
                "cancel_probe",
                "stop_sidecars:11",
                "stop_xray",
                "close_tun",
            ),
            events,
        )
        assertEquals(VpnConnectionState.DISCONNECTED, orchestrator.snapshot().state)
        assertFalse(orchestrator.snapshot().hasTunnel)
    }

    @Test
    fun `destroy waits for active transition before releasing resources and background work`() = runBlocking {
        val transitionMayFinish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val runtime = RecordingXrayRuntime(events)
        val tun = RecordingTunHandle(events)
        val scheduler = RecordingDelayedTaskScheduler(events)
        val delayedOwner = Any()
        val coordinator = VpnDestroyCoordinator(
            lifecycleMutex = Mutex(),
            cancelAndJoinActiveTransition = {
                events += "cancel_transition"
                transitionMayFinish.await()
                events += "join_transition"
            },
            releaseResources = {
                scheduler.cancel(delayedOwner)
                runtime.stop()
                tun.close()
            },
            cancelBackgroundWork = { events += "cancel_background" },
        )

        val destroy = async { coordinator.destroy() }
        yield()

        assertEquals(listOf("cancel_transition"), events)

        transitionMayFinish.complete(Unit)
        destroy.await()

        assertEquals(
            listOf(
                "cancel_transition",
                "join_transition",
                "cancel_delayed",
                "stop_xray",
                "close_tun",
                "cancel_background",
            ),
            events,
        )
    }

    @Test
    fun `destroy releases resources only after acquiring lifecycle ownership`() = runBlocking {
        val events = mutableListOf<String>()
        val lifecycleMutex = Mutex(locked = true)
        val coordinator = VpnDestroyCoordinator(
            lifecycleMutex = lifecycleMutex,
            cancelAndJoinActiveTransition = { events += "join_transition" },
            releaseResources = { events += "release_resources" },
            cancelBackgroundWork = { events += "cancel_background" },
        )

        val destroy = async { coordinator.destroy() }
        yield()

        assertEquals(listOf("join_transition"), events)
        assertFalse(destroy.isCompleted)

        lifecycleMutex.unlock()
        destroy.await()

        assertEquals(
            listOf("join_transition", "release_resources", "cancel_background"),
            events,
        )
    }

    @Test
    fun `destroy cancels background work even when resource close fails`() {
        val events = mutableListOf<String>()
        val coordinator = VpnDestroyCoordinator(
            lifecycleMutex = Mutex(),
            cancelAndJoinActiveTransition = { events += "join_transition" },
            releaseResources = {
                events += "release_resources"
                error("close failed")
            },
            cancelBackgroundWork = { events += "cancel_background" },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.destroy() }
        }
        assertEquals(
            listOf("join_transition", "release_resources", "cancel_background"),
            events,
        )
    }
}

private fun unusedPreparer(store: AtomicStoredSettingsStore) = VpnConnectionPreparer(
    store = store,
    currentNetworkKind = { EndpointRankingPolicy.NetworkKind.OTHER },
    resolveStart = { _, _, _, _ -> error("not used") },
    refreshAccessToken = { error("not used") },
)

private class InMemorySettingsStore : AtomicStoredSettingsStore {
    private var settings = DefaultStoredSettingsFactory.create()

    override fun load(): StoredSettings = settings

    override fun updateSettings(transform: (StoredSettings) -> StoredSettings): StoredSettings {
        settings = transform(settings)
        return settings
    }
}

private object UnusedTunInterfaceFactory : TunInterfaceFactory {
    override fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle? =
        error("not used")
}

private class RecordingConnectedSidecars(
    private val events: MutableList<String>,
) : VpnConnectedSidecars {
    private var owner: RuntimeOwner? = null

    override fun start(owner: RuntimeOwner, snapshot: StoredSettings) {
        this.owner = owner
        events += "start_sidecars:${owner.coreId}"
    }

    override fun stop(owner: RuntimeOwner?) {
        val current = this.owner ?: return
        events += "stop_sidecars:${current.coreId}"
        this.owner = null
    }
}

private class RecordingXrayRuntime(
    private val events: MutableList<String>,
) : XrayRuntime {
    override fun start(config: String, tunFd: Int): Boolean {
        events += "start_xray"
        return true
    }

    override fun stop() {
        events += "stop_xray"
    }

    override fun cancelMeasureDelay() {
        events += "cancel_probe"
    }

    override fun measureDelay(targetUrl: String, timeoutMillis: Long): XrayProbeResult =
        XrayProbeResult(delayMs = 1L)
}

private class BlockingFirstCancellationXrayRuntime(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : XrayRuntime {
    private val cancellationCalls = AtomicInteger(0)

    override fun start(config: String, tunFd: Int): Boolean = true

    override fun stop() = Unit

    override fun cancelMeasureDelay() {
        if (cancellationCalls.incrementAndGet() == 1) {
            entered.countDown()
            check(release.await(1L, TimeUnit.SECONDS)) { "timed out releasing first cancellation" }
        }
    }

    override fun measureDelay(targetUrl: String, timeoutMillis: Long): XrayProbeResult =
        XrayProbeResult(delayMs = 1L)
}

private class RecordingTunHandle(
    private val events: MutableList<String>,
) : TunHandle {
    override val fd: Int = 7

    override fun close() {
        events += "close_tun"
    }
}

private class RecordingDelayedTaskScheduler(
    private val events: MutableList<String>,
) : DelayedTaskScheduler {
    override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
        events += "schedule_delayed"
    }

    override fun cancel(owner: Any) {
        events += "cancel_delayed"
    }
}
