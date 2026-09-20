package com.noki.vpn

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.noki.vpn.data.BackendAppNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppBroadcastNotifierTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `plain notification does not erase an earlier security action`() {
        val security = post("security", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        post("plain")

        val intent = tap(security)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, action(intent))
        assertTrue(accepts(intent))
    }

    @Test
    fun `security notification does not add an action to an earlier plain notification`() {
        val plain = post("plain")
        post("security", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)

        val intent = tap(plain)

        assertNull(action(intent))
        assertNull(nonce(intent))
        assertFalse(accepts(intent))
    }

    @Test
    fun `consuming one notification action does not invalidate another notification`() {
        val first = post("first", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val second = post("second", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val firstIntent = tap(first)
        val secondIntent = tap(second)

        assertNotEquals(nonce(firstIntent), nonce(secondIntent))
        assertTrue(accepts(firstIntent))

        assertFalse(accepts(firstIntent))
        assertTrue(accepts(secondIntent))
    }

    @Test
    fun `different notification identifiers with equal hashes remain independent`() {
        val first = post("Aa", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val second = post("BB")

        assertEquals(2, manager.activeNotifications.size)
        assertTrue(accepts(tap(first)))
        assertNull(action(tap(second)))
    }

    @Test
    fun `reposting the same notification replaces its action without affecting another notification`() {
        val original = post("same", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val other = post("other", MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE)
        val replacement = post("same", "unsupported_action")

        assertEquals(2, manager.activeNotifications.size)
        assertEquals(original.contentIntent, replacement.contentIntent)
        val replacementIntent = tap(replacement)
        assertNull(action(replacementIntent))
        assertFalse(accepts(replacementIntent))
        assertTrue(accepts(tap(other)))
    }

    private fun post(id: String, action: String? = null): Notification {
        assertTrue(AppBroadcastNotifier.show(application, BackendAppNotification(
            id = id,
            title = id,
            message = "Message for $id",
            createdAt = "2026-09-05T00:00:00Z",
            action = action,
        )))
        return manager.activeNotifications.single {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == id
        }.notification
    }

    @Test
    fun `expired and evicted nonces cannot be consumed`() {
        val start = 1_000L
        val expired = AppNotificationActionNonceStore.issue(application, start)
        assertNull(AppNotificationActionNonceStore.validateAndConsume(
            application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, expired,
            start + AppNotificationActionNonceStore.TTL_MILLIS,
        ))
        val oldest = AppNotificationActionNonceStore.issue(application, start)
        repeat(AppNotificationActionNonceStore.MAX_NONCES) {
            AppNotificationActionNonceStore.issue(application, start + it + 1)
        }
        assertNull(AppNotificationActionNonceStore.validateAndConsume(
            application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, oldest,
            start + AppNotificationActionNonceStore.MAX_NONCES,
        ))
    }

    @Test
    fun `unsupported action cannot consume a valid nonce`() {
        val nonce = AppNotificationActionNonceStore.issue(application)
        assertNull(AppNotificationActionNonceStore.validateAndConsume(application, "unknown", nonce))
        assertEquals(AppNotificationAction.OpenSecurityUpdate,
            AppNotificationActionNonceStore.validateAndConsume(
                application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, nonce,
            ))
    }

    @Test
    fun `concurrent consumers accept a nonce only once`() {
        val nonce = AppNotificationActionNonceStore.issue(application)
        val start = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val workers = List(8) {
            Thread {
                start.await()
                if (AppNotificationActionNonceStore.validateAndConsume(
                        application, MainActivity.APP_NOTIFICATION_ACTION_OPEN_SECURITY_UPDATE, nonce,
                    ) != null) accepted.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, accepted.get())
    }

    private fun tap(notification: Notification): Intent {
        notification.contentIntent.send()
        return requireNotNull(shadowOf(application).nextStartedActivity)
    }

    private fun action(intent: Intent) = intent.getStringExtra(MainActivity.EXTRA_APP_NOTIFICATION_ACTION)

    private fun nonce(intent: Intent) = intent.getStringExtra(MainActivity.EXTRA_APP_NOTIFICATION_ACTION_NONCE)

    private fun accepts(intent: Intent) = AppNotificationActionNonceStore.validateAndConsume(
        context = application,
        action = action(intent),
        nonce = nonce(intent),
    ) != null
}
