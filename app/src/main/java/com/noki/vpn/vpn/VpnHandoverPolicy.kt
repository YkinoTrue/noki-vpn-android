package com.noki.vpn.vpn

object VpnHandoverPolicy {
    enum class Action {
        NoAction,
        FreshStart,
        RestartTunnel,
    }

    data class Plan(
        val action: Action,
        val strategy: VpnPreparationStrategy,
    )

    fun plan(
        hasTunnel: Boolean,
        activeSignature: String?,
        nextSignature: String?,
    ): Plan {
        return when {
            !hasTunnel -> Plan(
                Action.FreshStart,
                strategy = VpnPreparationStrategy.CachedFirst,
            )
            activeSignature == nextSignature -> Plan(
                Action.NoAction,
                strategy = VpnPreparationStrategy.CachedFirst,
            )
            else -> Plan(
                Action.RestartTunnel,
                strategy = VpnPreparationStrategy.CachedFirst,
            )
        }
    }
}
