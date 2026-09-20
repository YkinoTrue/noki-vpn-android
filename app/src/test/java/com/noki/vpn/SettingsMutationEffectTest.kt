package com.noki.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.StoredSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMutationEffectTest {
    @Test
    fun `persisted mutation returns requested runtime effect`() {
        val coordinator = SettingsMutationCoordinator(EffectSettingsStore())

        val result = coordinator.persistUiFields(AppUiState(), SettingsEffect.ApplyRuntimeSettings)

        assertEquals(SettingsEffect.ApplyRuntimeSettings, result.effect)
    }
}

private class EffectSettingsStore : AtomicStoredSettingsStore {
    private var value = DefaultStoredSettingsFactory.create()
    override fun load(): StoredSettings = value
    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        val (updated, result) = transform(value)
        value = updated
        return result
    }
}
