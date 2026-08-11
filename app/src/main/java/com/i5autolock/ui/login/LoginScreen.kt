package com.i5autolock.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.i5autolock.R
import com.i5autolock.domain.LogLevel
import com.i5autolock.ui.theme.PixelBand
import kotlinx.coroutines.launch

/**
 * Sign-in — one form, one job.
 *
 * The password field accepts either your BlueLink password *or* a pre-generated 48-char
 * refresh token; the ViewModel auto-detects which and routes accordingly. If the primary
 * headless flow fails, a secondary "sign in via in-app browser" option opens Hyundai's
 * real login page inside a locked-down WebView with a Cancel button and live log strip.
 *
 * Security: FLAG_SECURE blocks screenshots/recents thumbnails while credentials are visible.
 * The WebView is locked down (no file/content access, no password/form persistence, and
 * cookies are cleared when the screen closes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onSuccess: (String) -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    var redirectPrefix by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    LaunchedEffect(state) {
        (state as? LoginState.Success)?.let { onSuccess(it.email) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (authorizeUrl != null) stringResource(R.string.login_title_hyundai) else stringResource(R.string.login_title_bluelink)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (log.isNotEmpty()) {
                        TextButton(onClick = {
                            val text = log.asReversed().joinToString("\n") { it.message }
                            clipboard.setText(AnnotatedString(text))
                        }) { Text(stringResource(R.string.login_copy_log)) }
                        TextButton(onClick = { viewModel.clearLog() }) { Text(stringResource(R.string.login_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state is LoginState.InProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (log.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    log.take(30).forEach { entry ->
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (entry.level == LogLevel.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (authorizeUrl == null) {
                SignInForm(
                    email = email,
                    onEmailChange = { email = it },
                    password = password,
                    onPasswordChange = { password = it },
                    pin = pin,
                    onPinChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                    state = state,
                    onSignIn = {
                        viewModel.clearLog()
                        viewModel.logInfo("Sign in requested for ${email.trim()}")
                        viewModel.onSignIn(email, password, pin)
                    },
                    onOpenBrowser = {
                        scope.launch {
                            viewModel.clearLog()
                            viewModel.prepareWebLogin(email, pin)
                            redirectPrefix = viewModel.redirectPrefix()
                            authorizeUrl = viewModel.authorizeUrl()
                            viewModel.logInfo("Opening Hyundai's login page…")
                        }
                    },
                    onDemo = { viewModel.demoLogin() },
                )
            } else {
                WebViewSignIn(
                    authorizeUrl = authorizeUrl!!,
                    redirectPrefix = redirectPrefix,
                    onCancel = {
                        viewModel.logInfo("Sign-in cancelled.")
                        authorizeUrl = null
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                    },
                    onRedirect = viewModel::onRedirectCaptured,
                    onLog = viewModel::logInfo,
                    onError = viewModel::logError,
                )
            }
        }
    }
}

@Composable
private fun SignInForm(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    pin: String,
    onPinChange: (String) -> Unit,
    state: LoginState,
    onSignIn: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDemo: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PixelBand(
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = MaterialTheme.colorScheme.primary,
            cells = 20,
        )
        Text(
            stringResource(R.string.login_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = onPinChange,
            label = { Text(stringResource(R.string.login_pin)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        (state as? LoginState.Error)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            enabled = email.isNotBlank() && password.isNotBlank() && state !is LoginState.InProgress,
        ) { Text(stringResource(R.string.login_sign_in)) }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text(
            stringResource(R.string.login_browser_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onOpenBrowser,
            modifier = Modifier.fillMaxWidth(),
            enabled = email.isNotBlank() && state !is LoginState.InProgress,
        ) { Text(stringResource(R.string.login_browser_button)) }

        TextButton(
            onClick = onDemo,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.login_demo)) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewSignIn(
    authorizeUrl: String,
    redirectPrefix: String,
    onCancel: () -> Unit,
    onRedirect: (String) -> Unit,
    onLog: (String) -> Unit,
    onError: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Text(
                stringResource(R.string.login_webview_progress),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        savePassword = false
                        saveFormData = false
                        setGeolocationEnabled(false)
                    }
                    isVerticalScrollBarEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            return if (url.startsWith(redirectPrefix)) {
                                view?.stopLoading()
                                onLog("Captured redirect ✓")
                                onRedirect(url)
                                true
                            } else {
                                onLog("→ ${sanitizeForLog(url)}")
                                false
                            }
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            if (url == null) return
                            if (url.startsWith(redirectPrefix)) {
                                view?.stopLoading()
                                onLog("Captured redirect ✓")
                                onRedirect(url)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            onError(
                                "WebView error ${error?.errorCode}: ${error?.description} @ " +
                                    sanitizeForLog(request.url?.toString().orEmpty()),
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            onError(
                                "HTTP ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} @ " +
                                    sanitizeForLog(request.url?.toString().orEmpty()),
                            )
                        }
                    }
                    loadUrl(authorizeUrl)
                }
            },
        )
    }
}

private fun sanitizeForLog(url: String): String {
    if (url.isEmpty()) return "(empty)"
    val cut = url.substringBefore('?')
    val q = url.substringAfter('?', "")
    val safeQuery = if (q.isEmpty()) "" else {
        val keys = q.split('&')
            .mapNotNull { it.substringBefore('=', "").takeIf(String::isNotBlank) }
        "?" + keys.joinToString(",")
    }
    val trimmed = "$cut$safeQuery"
    return if (trimmed.length > 90) trimmed.take(90) + "…" else trimmed
}
