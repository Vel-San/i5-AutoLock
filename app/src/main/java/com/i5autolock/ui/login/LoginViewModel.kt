package com.i5autolock.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.i5autolock.data.bluelink.BlueLinkProvider
import com.i5autolock.data.bluelink.RegionConfig
import com.i5autolock.data.bluelink.eu.EuAuth
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
    activityLog: ActivityLog,
) : ViewModel() {

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    /** Live diagnostic log surfaced under the sign-in form. */
    val log: StateFlow<List<LogEntry>> = activityLog.entries

    private var emailHint: String = ""

    suspend fun regionConfig(): RegionConfig {
        val s = settingsRepo.settings.first()
        return provider.regionConfig(s.region)
    }

    suspend fun authorizeUrl(): String = EuAuth.buildAuthorizeUrl(regionConfig())

    suspend fun redirectPrefix(): String = regionConfig().redirectUri

    fun setEmailHint(email: String) { emailHint = email }

    /** Called by the WebView when it intercepts the redirect containing the code. */
    fun onRedirectCaptured(redirectUrl: String) {
        val code = EuAuth.extractAuthCode(redirectUrl)
        if (code == null) {
            _state.value = LoginState.Error("No authorization code found in redirect.")
            return
        }
        completeLogin(code)
    }

    /** Called when the user pastes the redirect URL/code from an external browser. */
    fun onPastedRedirect(input: String) {
        val code = EuAuth.extractAuthCodeLoose(input)
        if (code == null) {
            _state.value = LoginState.Error(
                "Couldn't find a code. Paste the full URL you were redirected to (it contains \"code=\").",
            )
            return
        }
        completeLogin(code)
    }

    /** Fully automatic: sign in with email + password; the token is generated on-device. */
    fun onPasswordSubmitted(email: String, password: String, pin: String) = viewModelScope.launch {
        emailHint = email
        _state.value = LoginState.InProgress
        if (pin.isNotBlank()) secureStore.savePin(pin.trim())
        val s = settingsRepo.settings.first()
        when (val result = provider.client(s).loginWithPassword(email, password)) {
            is CommandResult.Success -> {
                settingsRepo.update { it.copy(accountEmail = email.ifBlank { "EU account" }) }
                _state.value = LoginState.Success(email.ifBlank { "EU account" })
            }
            is CommandResult.Failure -> _state.value = LoginState.Error(result.reason)
            CommandResult.NotAuthenticated -> _state.value = LoginState.Error("Sign-in rejected.")
            CommandResult.RateLimited -> _state.value = LoginState.Error("Rate limited — try again shortly.")
        }
    }

    /** Reliable EU path: exchange a pre-obtained 48-char refresh token. PIN enables locking. */
    fun onRefreshTokenSubmitted(email: String, token: String, pin: String) = viewModelScope.launch {
        emailHint = email
        _state.value = LoginState.InProgress
        if (pin.isNotBlank()) secureStore.savePin(pin.trim())
        val s = settingsRepo.settings.first()
        when (val result = provider.client(s).loginWithRefreshToken(token)) {
            is CommandResult.Success -> {
                settingsRepo.update { it.copy(accountEmail = email.ifBlank { "EU account" }) }
                _state.value = LoginState.Success(email.ifBlank { "EU account" })
            }
            is CommandResult.Failure -> _state.value = LoginState.Error(result.reason)
            CommandResult.NotAuthenticated -> _state.value = LoginState.Error("Token rejected.")
            CommandResult.RateLimited -> _state.value = LoginState.Error("Rate limited — try again shortly.")
        }
    }

    private fun completeLogin(code: String) = viewModelScope.launch {
        _state.value = LoginState.InProgress
        val s = settingsRepo.settings.first()
        val client = provider.client(s)
        when (val result = client.login(emailHint, code)) {
            is CommandResult.Success -> {
                settingsRepo.update { it.copy(accountEmail = emailHint) }
                _state.value = LoginState.Success(emailHint)
            }
            is CommandResult.Failure -> _state.value = LoginState.Error(result.reason)
            CommandResult.NotAuthenticated -> _state.value = LoginState.Error("Authentication rejected.")
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
