package com.noki.vpn.vpn

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.noki.vpn.data.StoredSettings

internal interface TunHandle : AutoCloseable {
    val fd: Int
}

internal interface TunInterfaceFactory {
    fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle?
}

internal class TunInterfaceConfigurationException(
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause)

internal class AndroidTunInterfaceFactory(
    private val service: VpnService,
) : TunInterfaceFactory {
    override fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle? {
        val builder = service.Builder()
            .setSession(settings.profile.remark.ifBlank { "Noki Vpn" })
            .setMtu(VpnTunnelPolicy.MTU)
            .addAddress("10.10.0.2", 32)
            .addDnsServer(VpnTunnelPolicy.DNS_SERVER)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        builder.applyDefaultUnderlyingNetworkMetadata(underlay)

        val routingRules = AppVpnRoutingPolicy.rules(
            appPackageName = service.packageName,
            filterMode = settings.filterMode,
            selectedPackages = settings.selectedPackages,
            isInstalled = { candidate ->
                runCatching { service.packageManager.getApplicationInfo(candidate, 0) }.isSuccess
            },
        )
        if (routingRules is AppVpnRoutingRules.Rejected) {
            throw TunInterfaceConfigurationException(routingRules.failureReason)
        }
        check(routingRules is AppVpnRoutingRules.Ready)
        try {
            routingRules.allowedPackages.forEach(builder::addAllowedApplication)
            routingRules.disallowedPackages.forEach(builder::addDisallowedApplication)
        } catch (error: PackageManager.NameNotFoundException) {
            throw TunInterfaceConfigurationException("rules_error", error)
        }
        return builder.establish()?.let(::ParcelFileDescriptorTunHandle)
    }

    private fun VpnService.Builder.applyDefaultUnderlyingNetworkMetadata(
        snapshot: UnderlyingNetworkSnapshot?,
    ) {
        if (snapshot == null) return
        setUnderlyingNetworks(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setMetered(snapshot.vpnShouldBeMetered)
        }
    }
}

private class ParcelFileDescriptorTunHandle(
    private val descriptor: ParcelFileDescriptor,
) : TunHandle {
    override val fd: Int
        get() = descriptor.fd

    override fun close() {
        descriptor.close()
    }
}
