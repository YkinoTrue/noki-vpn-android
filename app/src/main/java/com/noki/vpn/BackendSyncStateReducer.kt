package com.noki.vpn

import com.noki.vpn.data.DeviceSession
import com.noki.vpn.data.PlanSummary
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VlessProfile

internal data class BackendSyncPatch(
    val userProfile: UserProfile,
    val devices: List<DeviceSession>,
    val locations: List<ServerLocation>,
    val plans: List<PlanSummary>,
    val profile: VlessProfile,
    val currentDeviceAccessRole: String,
    val androidUpdate: AndroidUpdateUiState,
)

internal data class BackendSyncOwner(
    val requestId: Long,
    val authEpoch: Long,
)

internal enum class BackendRefreshTrigger {
    Initial,
    UserRefresh,
    Stats,
}

internal object BackendRefreshArbitrationPolicy {
    fun shouldStart(
        active: BackendRefreshTrigger?,
        requested: BackendRefreshTrigger,
    ): Boolean = when (requested) {
        BackendRefreshTrigger.UserRefresh -> true
        BackendRefreshTrigger.Initial -> active == null || active == BackendRefreshTrigger.Stats
        BackendRefreshTrigger.Stats -> active == null
    }
}

internal object BackendSyncOwnershipPolicy {
    fun isCurrent(
        owner: BackendSyncOwner,
        currentRequestId: Long,
        currentAuthEpoch: Long,
    ): Boolean = owner.requestId == currentRequestId && owner.authEpoch == currentAuthEpoch
}

internal class BackendSyncRequestTracker {
    private var currentRequestId: Long = 0L

    fun next(authEpoch: Long): BackendSyncOwner {
        currentRequestId += 1L
        return BackendSyncOwner(currentRequestId, authEpoch)
    }

    fun invalidate() {
        currentRequestId += 1L
    }

    fun isCurrent(
        owner: BackendSyncOwner,
        currentAuthEpoch: Long,
    ): Boolean = BackendSyncOwnershipPolicy.isCurrent(owner, currentRequestId, currentAuthEpoch)
}

internal object BackendSyncStateReducer {
    fun apply(
        latest: AppUiState,
        patch: BackendSyncPatch,
        preserveAndroidUpdate: Boolean = false,
    ): AppUiState {
        val androidUpdate = if (
            preserveAndroidUpdate ||
            latest.androidUpdate.isChecking ||
            latest.androidUpdate.isDownloading || latest.androidUpdate.isReadyToInstall
        ) {
            latest.androidUpdate
        } else {
            patch.androidUpdate
        }
        return latest.copy(
            isAuthenticated = true,
            userProfile = patch.userProfile,
            devices = patch.devices,
            locations = patch.locations,
            plans = patch.plans,
            profile = patch.profile,
            currentDeviceAccessRole = patch.currentDeviceAccessRole,
            isAndroidUpdateAvailable = androidUpdate.update != null,
            androidUpdate = androidUpdate,
        )
    }
}
