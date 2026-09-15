package com.noki.vpn

import com.noki.vpn.data.AccountSecurityApi
import com.noki.vpn.data.AuthRefreshApi
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.BackendAccountPasswordChange
import com.noki.vpn.data.BackendAuthTokens
import com.noki.vpn.data.BackendUser
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.InMemoryAtomicStoredSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSecurityCoordinatorTest {
    @Test
    fun `password rotation commits replacement session before returning user`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        val api = FakeAccountSecurityApi(
            passwordResult = BackendAccountPasswordChange(
                user = user(hasPassword = true),
                tokens = BackendAuthTokens("new-access", "new-refresh", expiresInSeconds = 3_600),
            ),
        )
        val coordinator = AccountSecurityCoordinator(api, authSession)

        val result = coordinator.changePassword(
            context = ownerContext(),
            authAttempt = AuthSessionAttempt("access", 0),
            currentPassword = "old-password",
            newPassword = "new-password",
        )

        assertTrue(result.user.hasPassword)
        assertEquals("new-access", store.load().backendAccessToken)
        assertEquals("new-refresh", store.load().backendRefreshToken)
    }

    @Test
    fun `invited context is rejected before API side effects`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val api = FakeAccountSecurityApi()
        val coordinator = AccountSecurityCoordinator(
            api,
            AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi)),
        )

        val result = runCatching {
            coordinator.changeUsername(
                context = ownerContext().copy(isOwner = false),
                username = "new_name",
            )
        }

        assertTrue(result.isFailure)
        assertFalse(api.usernameCalled)
    }

    @Test
    fun `delayed password response cannot overwrite a replacement login session`() = runBlocking {
        val initial = DefaultStoredSettingsFactory.create().copy(
            isAuthenticated = true,
            backendAccessToken = "old-access",
            backendRefreshToken = "old-refresh",
        )
        val store = InMemoryAtomicStoredSettingsStore(initial)
        val authSession = AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi))
        authSession.restore(store.load())
        val attempt = checkNotNull(authSession.attempt())
        val response = CompletableDeferred<BackendAccountPasswordChange>()
        val started = CompletableDeferred<Unit>()
        val api = FakeAccountSecurityApi(
            passwordOperation = {
                started.complete(Unit)
                response.await()
            },
        )
        val coordinator = AccountSecurityCoordinator(api, authSession)

        val passwordChange = async {
            runCatching {
                coordinator.changePassword(
                    context = ownerContext().copy(accessToken = attempt.accessToken),
                    authAttempt = attempt,
                    currentPassword = null,
                    newPassword = "new-password",
                )
            }
        }
        started.await()
        authSession.commit(
            BackendAuthTokens(
                accessToken = "new-login-access",
                refreshToken = "new-login-refresh",
                expiresInSeconds = 7_200,
            ),
        )
        response.complete(
            BackendAccountPasswordChange(
                user = user(hasPassword = true),
                tokens = BackendAuthTokens("stale-access", "stale-refresh", expiresInSeconds = 3_600),
            ),
        )

        val result = passwordChange.await()

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("new-login-access", store.load().backendAccessToken)
        assertEquals("new-login-refresh", store.load().backendRefreshToken)
    }

    @Test
    fun `owner can unlink telegram through account security API`() = runBlocking {
        val store = InMemoryAtomicStoredSettingsStore(DefaultStoredSettingsFactory.create())
        val api = FakeAccountSecurityApi()
        val coordinator = AccountSecurityCoordinator(
            api,
            AuthSessionCoordinator(store, AuthTokenRefresher(store, NoRefreshApi)),
        )

        val result = coordinator.unlinkTelegram(ownerContext())

        assertTrue(api.unlinkTelegramCalled)
        assertFalse(result.telegramLinked)
    }

    private class FakeAccountSecurityApi(
        private val passwordResult: BackendAccountPasswordChange =
            BackendAccountPasswordChange(user(), null),
        private val passwordOperation: (suspend () -> BackendAccountPasswordChange)? = null,
    ) : AccountSecurityApi {
        var usernameCalled = false
        var unlinkTelegramCalled = false

        override suspend fun sendAccountEmailCode(
            token: String,
            email: String,
            currentDeviceId: String?,
            currentDeviceKey: String?,
            currentEmail: String?,
            currentVerificationCode: String?,
        ): Int = 60

        override suspend fun changeAccountEmail(
            token: String,
            email: String,
            verificationCode: String,
            currentDeviceId: String?,
            currentDeviceKey: String?,
            currentEmail: String?,
            currentVerificationCode: String?,
        ): BackendUser = user().copy(email = email, hasRealEmail = true)

        override suspend fun changeAccountPassword(
            token: String,
            currentPassword: String?,
            newPassword: String,
            currentDeviceId: String?,
            currentDeviceKey: String?,
        ): BackendAccountPasswordChange = passwordOperation?.invoke() ?: passwordResult

        override suspend fun changeAccountUsername(
            token: String,
            username: String,
            currentDeviceId: String?,
            currentDeviceKey: String?,
        ): BackendUser {
            usernameCalled = true
            return user().copy(username = username)
        }

        override suspend fun linkTelegramAccount(
            token: String,
            idToken: String,
            currentDeviceId: String?,
            currentDeviceKey: String?,
        ): BackendUser = user().copy(telegramLinked = true)

        override suspend fun unlinkTelegramAccount(
            token: String,
            currentDeviceId: String?,
            currentDeviceKey: String?,
        ): BackendUser {
            unlinkTelegramCalled = true
            return user().copy(telegramLinked = false)
        }
    }

    private object NoRefreshApi : AuthRefreshApi {
        override suspend fun refreshAuthToken(
            refreshToken: String,
            deviceId: String?,
            requestId: String?,
        ): BackendAuthTokens = error("refresh is not expected")
    }

    private fun ownerContext() = AccountSecurityContext(
        accessToken = "access",
        currentDeviceId = "device-1",
        currentDeviceKey = "device-key",
        isOwner = true,
    )

    private companion object {
        fun user(hasPassword: Boolean = false) = BackendUser(
            id = "user-1",
            username = "telegram_user",
            email = "tg_12345678901234@a.noki",
            avatarUrl = null,
            isActive = true,
            isAdmin = false,
            hasRealEmail = false,
            hasPassword = hasPassword,
            telegramLinked = true,
        )
    }
}
