package com.noki.vpn.vpn

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.BackendRuWireGuardConfig

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
        return establishWithRules(builder, settings, underlay, includeOwnApp = false)
    }

    fun establishWireGuard(
        settings: StoredSettings,
        underlay: UnderlyingNetworkSnapshot?,
        config: BackendRuWireGuardConfig,
    ): TunHandle? {
        val builder = service.Builder()
            .setSession("Noki WireGuard · RU relay")
            .setMtu(config.mtu)
            .addAddress(config.address.substringBefore('/'), 32)
            .addAddress("fd7a:6e6f:6b69::2", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        config.dns.forEach(builder::addDnsServer)
        return establishWithRules(builder, settings, underlay, includeOwnApp = true)
    }

    private fun establishWithRules(
        builder: VpnService.Builder,
        settings: StoredSettings,
        underlay: UnderlyingNetworkSnapshot?,
        includeOwnApp: Boolean,
    ): TunHandle? {
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
            (routingRules.allowedPackages + if (includeOwnApp &&
                settings.filterMode == com.noki.vpn.data.AppFilterMode.ONLY_SELECTED) {
                setOf(service.packageName)
            } else emptySet()).forEach(builder::addAllowedApplication)
            (routingRules.disallowedPackages - if (includeOwnApp) {
                setOf(service.packageName)
            } else emptySet()).forEach(builder::addDisallowedApplication)
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
