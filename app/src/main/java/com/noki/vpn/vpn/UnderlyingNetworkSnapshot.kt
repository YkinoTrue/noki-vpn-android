package com.noki.vpn.vpn

import com.noki.vpn.data.EndpointRankingPolicy

internal data class UnderlyingNetworkSnapshot(
    val kind: EndpointRankingPolicy.NetworkKind,
    val signature: String,
    val vpnShouldBeMetered: Boolean,
    val details: String,
    val network: android.net.Network? = null,
)
