package com.noki.vpn.vpn

import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.StoredSettings

internal object VpnServiceLogContext {
    fun serverLabel(settings: StoredSettings): String {
        if (settings.advancedSettings.multiHop.enabled) {
            val runtime = settings.profile.multiHop
            if (runtime != null) {
                val entry = runtime.entryNodeId.take(8)
                val exit = settings.profile.remark.removePrefix("Noki ").ifBlank { "Exit" }
                return "$entry → $exit"
            }
            return "MultiHop"
        }
        return serverLabel(
            selectedServerCode = settings.userProfile.selectedServerCode,
            remark = settings.profile.remark,
            language = settings.personalizationSettings.language,
        )
    }

    fun serverLabel(
        selectedServerCode: String,
        remark: String,
        language: AppLanguage,
    ): String {
        val serverName = remark.trim().removePrefix("Noki ").replace("_", " ").trim()
        if (serverName.isNotBlank() && serverName != "VPN") return serverName
        val code = selectedServerCode.trim().lowercase()
        return when (code) {
            "lv", "latvia" -> if (language == AppLanguage.RU) "Латвия" else "Latvia"
            "de", "germany" -> if (language == AppLanguage.RU) "Германия" else "Germany"
            "nl", "netherlands" -> if (language == AppLanguage.RU) "Нидерланды" else "Netherlands"
            "fi", "finland" -> if (language == AppLanguage.RU) "Финляндия" else "Finland"
            "pl", "poland" -> if (language == AppLanguage.RU) "Польша" else "Poland"
            "us", "usa", "united-states" -> if (language == AppLanguage.RU) "США" else "USA"
            else -> selectedServerCode.uppercase()
        }
    }
}
