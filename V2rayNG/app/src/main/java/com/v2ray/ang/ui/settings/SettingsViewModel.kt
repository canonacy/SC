package com.v2ray.ang.ui.settings

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SteamDlManager
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : BaseViewModel(application) {

    private val _systemVpnSettingsAvailable = MutableStateFlow(false)
    val systemVpnSettingsAvailable = _systemVpnSettingsAvailable.asStateFlow()

    suspend fun refreshSystemVpnSettingsAvailability() {
        _systemVpnSettingsAvailable.value = withContext(Dispatchers.IO) {
            // Android exposes the VPN page, not a direct link to the Always-on VPN switch.
            Intent(Settings.ACTION_VPN_SETTINGS).resolveActivity(getApplication<Application>().packageManager) != null
        }
    }

    /**
     * Checks for root access and requests it if necessary.
     * Updates [isLoading] during the process.
     */
    fun checkAndRequestRoot(onSuccess: () -> Unit) {
        launchLoading {
            val hasRoot = withContext(Dispatchers.IO) {
                RootManager.refresh()
            }
            if (hasRoot) {
                onSuccess()
            } else {
                toastError(R.string.toast_root_required)
            }
        }
    }

    /**
     * Validates if the given string is a valid observatory duration.
     * Shows error toast if invalid.
     * @return The trimmed value if valid, null otherwise.
     */
    fun validateObservatoryDuration(value: String): String? {
        val duration = value.trim()
        return if (AppConfig.OBSERVATORY_DURATION_PATTERN.matches(duration)) {
            duration
        } else {
            toastError(R.string.toast_invalid_observatory_duration)
            null
        }
    }

    /**
     * Validates if the given string is a valid observatory sampling value.
     * Shows error toast if invalid.
     * @return The value if valid, null otherwise.
     */
    fun validateObservatorySampling(value: String): String? {
        val sampling = value.trim().toIntOrNull()?.takeIf { it > 0 }
        return if (sampling != null) {
            sampling.toString()
        } else {
            toastError(R.string.toast_invalid_observatory_sampling)
            null
        }
    }

    private val _steamdlEnabled = MutableStateFlow(readBoolSetting(AppConfig.PREF_STEAMDL_ENABLED, false))
    val steamdlEnabled: StateFlow<Boolean>
        get() = _steamdlEnabled.asStateFlow()

    private val _steamdlToken = MutableStateFlow(readStringSetting(AppConfig.PREF_STEAMDL_TOKEN, ""))
    val steamdlToken: StateFlow<String>
        get() = _steamdlToken.asStateFlow()

    private val _steamdlStatus = MutableStateFlow<SteamDlUiState>(SteamDlUiState.Idle)
    val steamdlStatus: StateFlow<SteamDlUiState>
        get() = _steamdlStatus.asStateFlow()

    /** Persists the SteamDL switch and kicks off a config fetch when enabling. */
    fun onSteamdlEnabledChanged(enabled: Boolean) {
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_STEAMDL_ENABLED, enabled)
        } catch (t: Throwable) {
            // Unit tests run without MMKV; in-memory state below stays correct.
        }
        _steamdlEnabled.value = enabled
        if (enabled) {
            refreshSteamDlConfig()
        } else {
            _steamdlStatus.value = SteamDlUiState.Idle
        }
    }

    /** Persists the SteamDL subscription token sent as Auth-Token by the bridge. */
    fun onSteamdlTokenChanged(value: String) {
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_STEAMDL_TOKEN, value)
        } catch (t: Throwable) {
            // Unit tests run without MMKV; in-memory state below stays correct.
        }
        _steamdlToken.value = value
    }

    /** Fetches client_config.json and surfaces the outcome as [SteamDlUiState]. */
    fun refreshSteamDlConfig() {
        if (!_steamdlEnabled.value) {
            return
        }
        viewModelScope.launch {
            _steamdlStatus.value = SteamDlUiState.Loading
            val result = SteamDlManager.refresh(app)
            _steamdlStatus.value = result.fold(
                onSuccess = { SteamDlUiState.Success(it.domains.size) },
                onFailure = { SteamDlUiState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    private fun readBoolSetting(key: String, defaultValue: Boolean): Boolean {
        return try {
            MmkvManager.decodeSettingsBool(key, defaultValue)
        } catch (t: Throwable) {
            defaultValue
        }
    }

    private fun readStringSetting(key: String, defaultValue: String): String {
        return try {
            MmkvManager.decodeSettingsString(key, defaultValue) ?: defaultValue
        } catch (t: Throwable) {
            defaultValue
        }
    }
}

/**
 * Durable SteamDL config status owned by [SettingsViewModel].
 */
sealed interface SteamDlUiState {
    object Idle : SteamDlUiState
    object Loading : SteamDlUiState
    data class Success(val domains: Int) : SteamDlUiState
    data class Error(val message: String) : SteamDlUiState
}
