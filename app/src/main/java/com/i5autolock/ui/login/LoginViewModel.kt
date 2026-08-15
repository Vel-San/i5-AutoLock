package com.i5autolock.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.model.CommandResult
import com.i5autolock.data.secure.SecureStore
import com.i5autolock.data.settings.SettingsRepository
import com.i5autolock.domain.ActivityLog
import com.i5autolock.domain.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginState {
    data object Idle : LoginState
    data object InProgress : LoginState
    data class Error(val message: String) : LoginState
    data class Success(val email: String) : LoginState
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val provider: BlueLinkProvider,
    private val secureStore: SecureStore,
    private val activityLog: ActivityLog,
) : ViewModel() {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    /** Live diagnostic log surfaced under the sign-in form. */
    val log: StateFlow<List<LogEntry>> = activityLog.entries

    fun logInfo(message: String) = activityLog.add(com.i5autolock.domain.LogLevel.INFO, message)
    fun clearLog() = activityLog.clear()

    /**
     * The single EU sign-in path: email + password (+ optional 4-digit lock PIN). The session is
     * generated on-device via the OneApp/CCI flow — no browser, no reCAPTCHA, no refresh token.
     */
    fun onSignIn(email: String, password: String, pin: String) = viewModelScope.launch {
        _state.value = LoginState.InProgress
        if (pin.isNotBlank()) secureStore.savePin(pin.trim())
        val s = settingsRepo.settings.first()
        when (val result = provider.client(s).loginWithPassword(email, password)) {
            is CommandResult.Success -> {
                val label = email.ifBlank { "EU account" }
                settingsRepo.update { it.copy(accountEmail = label) }
                _state.value = LoginState.Success(label)
            }
            is CommandResult.Failure -> _state.value = LoginState.Error(result.reason)
            CommandResult.NotAuthenticated -> _state.value = LoginState.Error("Sign-in rejected.")
            CommandResult.RateLimited -> _state.value = LoginState.Error("Rate limited — try again shortly.")
        }
    }

    /** Demo shortcut: signs in the fake client instantly. */
    fun demoLogin() = viewModelScope.launch {
        _state.value = LoginState.InProgress
        settingsRepo.update { it.copy(demoMode = true) }
        provider.invalidate()
        val s = settingsRepo.settings.first()
        provider.client(s).login("demo@i5autolock.app", "demo")
        settingsRepo.update { it.copy(accountEmail = "demo@i5autolock.app") }
        _state.value = LoginState.Success("demo@i5autolock.app")
    }
}
