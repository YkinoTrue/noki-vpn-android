package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.VpnServer

internal fun multiHopSelectionError(settings: MultiHopSettings, locations: List<ServerLocation>): String? {
    if (!settings.enabled) return null
    val entry = settings.entry ?: return "choose_entry"
    val exit = settings.exit ?: return "choose_exit"
    fun candidates(selection: HopSelection, entryRole: Boolean): Set<String> = locations
        .flatMap(ServerLocation::servers)
        .filter { it.isOnline && (if (entryRole) it.multiHopEntryAvailable else it.multiHopExitAvailable) }
        .filter { server -> when (selection.kind) {
            ServerSelectionMode.COUNTRY -> server.countryCode.equals(selection.countryCode, ignoreCase = true)
            ServerSelectionMode.SERVER -> server.id == selection.nodeId
            ServerSelectionMode.AUTO -> false
        } }
        .map(VpnServer::id)
        .toSet()
    val entries = candidates(entry, true)
    val exits = candidates(exit, false)
    if (entries.isEmpty()) return "entry_unavailable"
    if (exits.isEmpty()) return "exit_unavailable"
    if (entries.none { itEntry -> exits.any { itExit -> itEntry != itExit } }) return "same_node"
    return null
}

@Composable
fun MultiHopScreen(state: AppUiState, viewModel: MainViewModel) {
    val language = state.personalizationSettings.language
    val context = LocalContext.current
    var draft by remember { mutableStateOf(state.advancedSettings.multiHop) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var attemptedSave by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.isAuthenticated) viewModel.refreshServers() }
    val error = multiHopSelectionError(draft, state.locations)

    Column(
        modifier = Modifier.fillMaxSize().background(AdvancedBgLighter).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("MultiHop", color = AdvancedTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            tr(language, "Выберите входную и выходную ноду. VPN подключится к входу, а сайты увидят IP выхода.",
                "Choose an entry and exit node. VPN connects to the entry; sites see the exit IP."),
            color = AdvancedTextSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr(language, "Включить MultiHop", "Enable MultiHop"), color = AdvancedTextPrimary,
                modifier = Modifier.weight(1f))
            Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) },
                modifier = Modifier.semantics { contentDescription = "MultiHop" })
        }
        if (draft.enabled) {
            HopPicker(
                title = tr(language, "Вход", "Entry"), selection = draft.entry, locations = state.locations,
                entryRole = true, language = language, expanded = expanded == "entry",
                onExpand = { expanded = if (expanded == "entry") null else "entry" },
                onSelect = { draft = draft.copy(entry = it); expanded = null },
            )
            HopPicker(
                title = tr(language, "Выход", "Exit"), selection = draft.exit, locations = state.locations,
                entryRole = false, language = language, expanded = expanded == "exit",
                onExpand = { expanded = if (expanded == "exit") null else "exit" },
                onSelect = { draft = draft.copy(exit = it); expanded = null },
            )
            if (attemptedSave && error != null) Text(
                when (error) {
                    "choose_entry" -> tr(language, "Выберите вход", "Choose an entry")
                    "choose_exit" -> tr(language, "Выберите выход", "Choose an exit")
                    "entry_unavailable" -> tr(language, "Входная нода недоступна", "Entry node is unavailable")
                    "exit_unavailable" -> tr(language, "Выходная нода недоступна", "Exit node is unavailable")
                    else -> tr(language, "Нужны две разные ноды", "Choose two different nodes")
                }, color = Color(0xFFFF8E7D),
            )
            if (state.advancedSettings.youtubeDirectDpiEnabled) Text(
                tr(language, "YouTube выходит через специальный сервер после MultiHop.",
                    "YouTube exits through its special server after MultiHop."),
                color = AdvancedTextSecondary,
            )
            Text(tr(language, "Правила обхода сайтов и ручной выбор протокола временно неактивны.",
                "Site bypass rules and manual protocol selection are temporarily inactive."),
                color = AdvancedTextSecondary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                attemptedSave = true
                if (error == null) {
                    viewModel.setMultiHop(draft)
                    viewModel.goBack()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AdvancedAccentPrimary, contentColor = AdvancedBgLighter),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(tr(language, "Сохранить", "Save")) }
        Button(onClick = viewModel::goBack, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AdvancedBgSoft)) {
            Text(tr(language, "Отмена", "Cancel"))
        }
    }
}

@Composable
private fun HopPicker(
    title: String,
    selection: HopSelection?,
    locations: List<ServerLocation>,
    entryRole: Boolean,
    language: AppLanguage,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSelect: (HopSelection) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val eligible = locations.flatMap(ServerLocation::servers).filter {
        it.isOnline && (if (entryRole) it.multiHopEntryAvailable else it.multiHopExitAvailable)
    }
    val selectedLabel = when (selection?.kind) {
        ServerSelectionMode.COUNTRY -> locations.firstOrNull { it.countryCode.equals(selection.countryCode, true) }?.country
        ServerSelectionMode.SERVER -> locations.flatMap(ServerLocation::servers)
            .firstOrNull { it.id == selection.nodeId }?.name
        else -> null
    } ?: if (selection != null) tr(language, "Сохранённый выбор недоступен", "Saved choice unavailable")
        else tr(language, "Выбрать", "Choose")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = AdvancedTextPrimary, fontWeight = FontWeight.Medium)
        Text(selectedLabel, color = AdvancedTextPrimary,
            modifier = Modifier.fillMaxWidth().border(BorderStroke(1.dp, AdvancedStroke), shape)
                .clickable(onClick = onExpand).semantics { contentDescription = "$title: $selectedLabel" }
                .padding(18.dp))
        if (expanded) {
            if (eligible.isEmpty()) Text(tr(language, "Нет доступных нод", "No available nodes"),
                color = AdvancedTextSecondary)
            locations.filter { location -> eligible.any { it.countryCode.equals(location.countryCode, true) } }
                .distinctBy(ServerLocation::countryCode)
                .forEach { location ->
                    Text(location.country, color = AdvancedTextPrimary,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onSelect(HopSelection(ServerSelectionMode.COUNTRY, countryCode = location.countryCode))
                        }.padding(12.dp))
                    eligible.filter { it.countryCode.equals(location.countryCode, true) }.forEach { server ->
                        Text("    ${server.name}", color = AdvancedTextSecondary,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(HopSelection(ServerSelectionMode.SERVER, nodeId = server.id))
                            }.padding(12.dp))
                    }
                }
        }
    }
}
