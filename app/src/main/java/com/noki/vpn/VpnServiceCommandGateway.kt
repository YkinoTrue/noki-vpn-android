package com.noki.vpn

import android.content.Context
import com.noki.vpn.vpn.AppVpnService

internal interface VpnServiceCommandGateway {
    fun start()
    fun startTemporary()
    fun stop()
    fun stopAndRevokeTemporary()
    fun restart()
    fun applyRuntimeSettings()
}

internal class AndroidVpnServiceCommandGateway(
    private val context: Context,
) : VpnServiceCommandGateway {
    override fun start() {
        context.startForegroundService(AppVpnService.startIntent(context, refreshSession = true))
    }

    override fun startTemporary() {
        context.startForegroundService(AppVpnService.temporaryStartIntent(context))
    }

    override fun stop() {
        context.startService(AppVpnService.stopIntent(context))
    }

    override fun stopAndRevokeTemporary() {
        context.startService(AppVpnService.stopAndRevokeTemporaryIntent(context))
    }

    override fun restart() {
        context.startService(AppVpnService.restartIntent(context))
    }

    override fun applyRuntimeSettings() {
        context.startService(AppVpnService.applySettingsIntent(context))
    }
}
