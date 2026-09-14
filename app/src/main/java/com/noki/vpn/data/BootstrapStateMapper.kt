package com.noki.vpn.data

import java.util.Locale

object BootstrapStateMapper {
    data class Result(
        val userProfile: UserProfile,
        val devices: List<DeviceSession>,
        val locations: List<ServerLocation>,
        val plans: List<PlanSummary>,
        val profile: VlessProfile,
        val currentDeviceAccessRole: String,
    )

    fun initialDevices(): List<DeviceSession> = emptyList()

    fun initialLocations(): List<ServerLocation> = emptyList()

    fun initialUsageBars(): List<UsageBar> = emptyList()

    fun mapBootstrap(
        bootstrap: BootstrapPayload,
        language: AppLanguage,
        currentUserProfile: UserProfile,
        currentProfile: VlessProfile,
        advancedSettings: AdvancedSettings,
        endpointOptions: List<VpnEndpointOption>,
        currentDeviceId: String,
        currentDeviceKey: String,
        previousDeviceAccessRole: String,
        clientLatencyByTarget: Map<String, Int>,
    ): Result {
        val locations = if (bootstrap.locations.isNotEmpty()) {
            mapLocations(
                locations = bootstrap.locations,
                language = language,
                clientLatencyByTarget = clientLatencyByTarget,
            )
        } else {
            initialLocations()
        }
        val selectedCountryCode = selectedCountryCode(
            locations = locations,
            backendLocations = bootstrap.locations,
            currentCountryCode = currentUserProfile.selectedCountryCode,
            legacyServerCode = currentUserProfile.selectedServerCode,
        )
        val accessRole = bootstrap.devices.firstOrNull { device ->
            device.id == currentDeviceId || device.deviceKey == currentDeviceKey
        }?.accessRole?.ifBlank { null } ?: previousDeviceAccessRole.ifBlank { "owner" }

        return Result(
            userProfile = currentUserProfile.copy(
                backendUserId = bootstrap.user.id,
                username = bootstrap.user.username,
                email = bootstrap.user.email,
                avatarUri = bootstrap.user.avatarUrl,
                hasRealEmail = bootstrap.user.hasRealEmail,
                hasPassword = bootstrap.user.hasPassword,
                telegramLinked = bootstrap.user.telegramLinked,
                selectedPlanCode = PlanCode.fromBackend(
                    bootstrap.subscription.planCode,
                    currentUserProfile.selectedPlanCode,
                ),
                selectedPlanCodeRaw = bootstrap.subscription.planCode?.ifBlank { null }
                    ?: currentUserProfile.selectedPlanCodeRaw,
                selectedPlanName = bootstrap.subscription.planName,
                selectedPlanTier = bootstrap.subscription.planTier,
                selectedPlanBadgeColor = bootstrap.subscription.planBadgeColor,
                selectedCountryCode = if (currentUserProfile.serverSelectionMode == ServerSelectionMode.AUTO) {
                    currentUserProfile.selectedCountryCode
                } else selectedCountryCode,
                trafficUsedGb = bootstrap.subscription.trafficUsedGb,
                trafficLimitGb = bootstrap.subscription.trafficLimitGb,
                subscriptionExpiresAt = bootstrap.subscription.expiresAt,
                subscriptionStatus = bootstrap.subscription.status,
            ),
            devices = mapDevices(
                devices = bootstrap.devices,
                language = language,
                currentDeviceId = currentDeviceId,
                currentDeviceKey = currentDeviceKey,
            ),
            locations = locations,
            plans = mapPlans(bootstrap.plans, language),
            profile = RuntimeProfilePolicy.profileAfterProtocolChange(
                profile = currentProfile,
                protocol = advancedSettings.protocol,
            ),
            currentDeviceAccessRole = accessRole,
        )
    }

    fun selectedCountryCode(
        locations: List<ServerLocation>,
        backendLocations: List<BackendLocation>,
        currentCountryCode: String,
        legacyServerCode: String,
    ): String {
        val normalizedCurrent = currentCountryCode.trim().uppercase(Locale.ROOT)
        if (normalizedCurrent.length == 2) return normalizedCurrent
        val migratedCountry = backendLocations
            .firstOrNull { it.code.equals(legacyServerCode.trim(), ignoreCase = true) }
            ?.countryCode
            ?.trim()
            ?.uppercase(Locale.ROOT)
        return locations.firstOrNull { it.code.equals(normalizedCurrent, ignoreCase = true) }?.code
            ?: locations.firstOrNull { it.code.equals(migratedCountry, ignoreCase = true) }?.code
            ?: locations.firstOrNull { it.isOnline }?.code
            ?: locations.firstOrNull()?.code
            ?: normalizedCurrent
    }

    fun mapDevices(
        devices: List<BackendDevice>,
        language: AppLanguage,
        currentDeviceId: String,
        currentDeviceKey: String,
    ): List<DeviceSession> {
        return devices.map { device ->
            val displayDevice = if (device.platform.equals("android", ignoreCase = true)) {
                device.copy(deviceName = AndroidDeviceInfo.displayName(device.deviceName))
            } else device
            val isCurrent = device.id == currentDeviceId || device.deviceKey == currentDeviceKey
            DeviceSession(
                id = device.id,
                title = device.customName?.trim()?.takeIf(String::isNotBlank)
                    ?: displayDevice.deviceName.ifBlank { fallbackDeviceName(device.platform, language) },
                subtitle = buildDeviceSubtitle(displayDevice, language, isCurrent),
                isCurrent = isCurrent,
                isOnline = isCurrent || device.isActive,
                isActive = device.isActive,
                accessRole = device.accessRole,
            )
        }
    }

    fun mapLocations(
        locations: List<BackendLocation>,
        language: AppLanguage,
        clientLatencyByTarget: Map<String, Int>,
    ): List<ServerLocation> {
        return locations
            .filter { it.countryCode.isNotBlank() }
            .groupBy { it.countryCode.trim().uppercase(Locale.ROOT) }
            .map { (countryCode, members) ->
            val onlineMembers = members.filter { it.isOnline }
            val selectableMembers = onlineMembers.ifEmpty { members }
            val representative = selectableMembers.minByOrNull { it.loadPercent ?: Int.MAX_VALUE }
                ?: members.first()
            val targetKey = clientLatencyTargetKey(countryCode, representative.entryHost)
            val servers = members.flatMap { it.servers }.distinctBy { it.id }.map { server ->
                server.copy(latencyMs = clientLatencyTargetKey(server)?.let(clientLatencyByTarget::get))
            }
            val capacities = onlineMembers.mapNotNull { it.capacityMbps }
            val downloads = onlineMembers.mapNotNull { it.downloadMbps }
            val uploads = onlineMembers.mapNotNull { it.uploadMbps }
            ServerLocation(
                code = countryCode,
                countryCode = countryCode,
                country = members.firstNotNullOfOrNull { location ->
                    (if (language == AppLanguage.RU) location.nameRu else location.nameEn)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                } ?: Locale("", countryCode).getDisplayCountry(Locale(language.tag)).ifBlank { countryCode },
                city = "",
                host = representative.entryHost,
                capacityMbps = capacities.takeIf { it.isNotEmpty() }?.sum(),
                downloadMbps = downloads.takeIf { it.isNotEmpty() }?.sum(),
                uploadMbps = uploads.takeIf { it.isNotEmpty() }?.sum(),
                latencyMs = if (servers.isNotEmpty()) servers.filter { it.isOnline }.mapNotNull { it.latencyMs }.minOrNull()
                    else targetKey?.let(clientLatencyByTarget::get),
                loadPercent = onlineMembers.mapNotNull { it.loadPercent }.minOrNull(),
                isOnline = if (servers.isNotEmpty()) servers.any { it.isOnline } else onlineMembers.isNotEmpty(),
                servers = servers,
            )
        }.sortedBy { it.country.lowercase(Locale.ROOT) }
    }

    fun initialPlans(): List<PlanSummary> = emptyList()

    fun mapPlans(
        plans: List<BackendPlan>,
        language: AppLanguage,
    ): List<PlanSummary> {
        return plans
            .filter { it.isActive }
            .sortedWith(compareBy<BackendPlan> { it.sortOrder }.thenBy { it.billingPeriodMonths })
            .map { plan ->
                PlanSummary(
                    code = plan.code,
                    tier = plan.tier,
                    title = plan.tier.replaceFirstChar { char -> char.titlecase(Locale.ROOT) },
                    devices = plan.deviceLimit,
                    trafficLimitGb = plan.trafficLimitGb,
                    trafficLabel = plan.trafficLimitGb?.let { TrafficFormat.gigabytes(it, language).label }
                        ?: tr(language, "Безлимит", "Unlimited"),
                    monthlyPriceRub = plan.priceRub,
                    yearlyMonthlyPriceRub = if (plan.billingPeriodMonths >= 12) plan.monthlyEquivalentRub else null,
                    badgeColor = plan.badgeColor,
                    headline = plan.headline?.trim()?.takeIf { it.isNotBlank() },
                    features = plan.features,
                    isRecommended = plan.tier.equals("pro", ignoreCase = true),
                )
            }
    }

    private fun fallbackDeviceName(
        platform: String,
        language: AppLanguage,
    ): String {
        return when (platform.lowercase(Locale.ROOT)) {
            "android" -> if (language == AppLanguage.RU) "Android устройство" else "Android device"
            "ios" -> "iPhone"
            "windows" -> "Windows PC"
            "macos" -> "Mac"
            else -> if (language == AppLanguage.RU) "Устройство" else "Device"
        }
    }

    private fun buildDeviceSubtitle(
        device: BackendDevice,
        language: AppLanguage,
        isCurrent: Boolean,
    ): String {
        val platformLabel = when (device.platform.lowercase(Locale.ROOT)) {
            "android" -> "Android"
            "ios" -> "iOS"
            "windows" -> "Windows"
            "macos" -> "macOS"
            else -> device.platform.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        val statusLabel = when {
            isCurrent -> tr(language, "В сети", "Online")
            device.lastSeenAt != null -> tr(language, "Недавно", "Recently")
            else -> tr(language, "Неактивно", "Inactive")
        }
        val modelLabel = device.deviceName.trim()
            .takeIf { device.customName?.isNotBlank() == true && it.isNotBlank() }
        return listOfNotNull(modelLabel, platformLabel, statusLabel).joinToString(" • ")
    }

    private fun tr(
        language: AppLanguage,
        russian: String,
        english: String,
    ): String {
        return if (language == AppLanguage.RU) russian else english
    }
}
