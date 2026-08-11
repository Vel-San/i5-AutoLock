package com.i5autolock.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.i5autolock.domain.LogLevel
import com.i5autolock.ui.theme.PixelBand
import kotlinx.coroutines.launch

/**
 * EU login. Two methods:
 *  - External browser (recommended): opens Hyundai's real login in the user's own browser
 *    (Chrome/Firefox/Brave); the user pastes the resulting redirect URL/code back in.
 *  - In-app WebView: signs in inside a locked-down, screenshot-blocked window and intercepts
 *    the redirect automatically.
 *
 * Security: the window is marked FLAG_SECURE, the WebView is locked down (no file/content
 * access, no password/form persistence), and cookies are cleared when leaving.
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
    var authorizeUrl by remember { mutableStateOf<String?>(null) }
    var redirectPrefix by remember { mutableStateOf("") }
    var browserOpened by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var webAutofillEmail by remember { mutableStateOf("") }
    var webAutofillPassword by remember { mutableStateOf<String?>(null) }

    // Block screenshots / recents thumbnail while credentials are on screen.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            // Never leave a Hyundai web session behind.
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
                title = { Text("Sign in to BlueLink") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (log.isNotEmpty()) {
                        TextButton(onClick = {
                            val text = log.asReversed().joinToString("\n") { it.message }
                            clipboard.setText(AnnotatedString(text))
                        }) { Text("Copy log") }
                        TextButton(onClick = { viewModel.clearLog() }) { Text("Clear") }
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

            // Log panel — ALWAYS visible so problems are never hidden by the WebView.
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
                Column(
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PixelBand(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        cells = 20,
                    )
                    Text(
                        "Sign in with your Hyundai BlueLink email and password. AutoLock opens " +
                            "Hyundai's real login page, signs in for you, and captures the token " +
                            "on your device — nothing is shared elsewhere.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("BlueLink email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (state as? LoginState.Error)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                    }

                    // Recommended: fully automatic email + password sign-in.
                    Text("Recommended", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("BlueLink password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("BlueLink PIN (needed to lock)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.clearLog()
                                viewModel.logInfo("Sign in requested for ${email.trim()}")
                                viewModel.onPasswordSubmitted(email, password, pin)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = email.isNotBlank() && password.isNotBlank(),
                    ) { Text("Sign in") }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    // Advanced: paste a pre-generated 48-char refresh token.
                    Text("Advanced: use a refresh token", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = refreshToken,
                        onValueChange = { refreshToken = it },
                        label = { Text("EU refresh token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { viewModel.onRefreshTokenSubmitted(email, refreshToken, pin) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = refreshToken.isNotBlank(),
                    ) { Text("Sign in with refresh token") }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    // Alternative: open the login in the user's own browser.
                    Text("Or try browser sign-in", style = MaterialTheme.typography.labelLarge)
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.setEmailHint(email)
                                val url = viewModel.authorizeUrl()
                                openInBrowser(context, url)
                                browserOpened = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = email.isNotBlank(),
                    ) { Text("Open in your browser (Chrome, Firefox…)") }

                    if (browserOpened) {
                        Text(
                            "After you sign in, your browser lands on a blank/error page. Copy that " +
                                "page's full URL (it contains \"code=\") and paste it here:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            label = { Text("Paste redirect URL or code") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.onPastedRedirect(pasted) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = pasted.isNotBlank(),
                        ) { Text("Finish sign-in") }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    // Alternative: in-app WebView, sign in manually (no autofill).
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                viewModel.setEmailHint(email)
                                webAutofillPassword = null
                                redirectPrefix = viewModel.redirectPrefix()
                                authorizeUrl = viewModel.authorizeUrl()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = email.isNotBlank(),
                    ) { Text("Sign in inside the app instead") }

                    TextButton(
                        onClick = { viewModel.demoLogin() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Skip and use Demo mode instead") }
                }
            } else {
                val autofillEmail = webAutofillEmail
                val autofillPass = webAutofillPassword
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            authorizeUrl = null
                            webAutofillPassword = null
                            viewModel.logInfo("Sign-in cancelled.")
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                        }) { Text("Cancel") }
                        Text(
                            "Signing in on Hyundai's page…",
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
                                // Fresh Akamai session every time — a stale/flagged cookie from a prior
                                // attempt makes Hyundai reject even a valid login as an "abusing request".
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                // Cookies are REQUIRED for the OAuth session; without them Hyundai
                                // returns "Session Timedout : 401" immediately.
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                with(settings) {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                databaseEnabled = true
                                // Present as a normal mobile browser; Hyundai's login rejects the
                                // default WebView user-agent (the "; wv" token) with a 401.
                                userAgentString =
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                // Lock down everything the login page doesn't need.
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
                                        viewModel.logInfo("Captured redirect ✓")
                                        viewModel.onRedirectCaptured(url)
                                        true
                                    } else {
                                        viewModel.logInfo("→ ${sanitizeForLog(url)}")
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
                                        viewModel.logInfo("Captured redirect ✓")
                                        viewModel.onRedirectCaptured(url)
                                    } else {
                                        viewModel.logInfo("Loading ${sanitizeForLog(url)}")
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    val url = request?.url?.toString().orEmpty()
                                    val isMainFrame = request?.isForMainFrame == true
                                    if (!isMainFrame) return
                                    viewModel.logError(
                                        "WebView error ${error?.errorCode}: ${error?.description} @ ${sanitizeForLog(url)}",
                                    )
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: android.webkit.WebResourceResponse?,
                                ) {
                                    val url = request?.url?.toString().orEmpty()
                                    val isMainFrame = request?.isForMainFrame == true
                                    if (!isMainFrame) return
                                    viewModel.logError(
                                        "HTTP ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} @ ${sanitizeForLog(url)}",
                                    )
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url == null || url.startsWith(redirectPrefix)) return
                                    // Autofill and submit Hyundai's real login form for the user.
                                    if (autofillPass != null) {
                                        view?.evaluateJavascript(
                                            autofillJs(autofillEmail, autofillPass), null,
                                        )
                                    }
                                }
                            }
                            loadUrl(authorizeUrl!!)
                        }
                    },
                )
                }
            }
        }
    }
}

/** Opens the authorize URL in the user's chosen external browser (Chrome, Firefox, Brave…). */
private fun openInBrowser(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(view, "Open with").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/** Log-safe URL: strips query strings that carry secrets and trims length. */
private fun sanitizeForLog(url: String): String {
    if (url.isEmpty()) return "(empty)"
    val cut = url.substringBefore('?')
    val q = url.substringAfter('?', "")
    val safeQuery = if (q.isEmpty()) "" else {
        val keys = q.split('&').mapNotNull { it.substringBefore('=', "").takeIf(String::isNotBlank) }
        "?" + keys.joinToString(",")
    }
    val trimmed = "$cut$safeQuery"
    return if (trimmed.length > 90) trimmed.take(90) + "…" else trimmed
}

/**
 * JS that fills Hyundai's real login form (email + password) and submits it. Handles both
 * single-page and two-step (email → password) layouts, and dispatches input/change events so
 * the page's own framework registers the values. Injected on each non-redirect page load.
 */
private fun autofillJs(email: String, password: String): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
    val js = """
        (function(){try{
          var EM='__E__',PW='__P__';
          function setVal(el,v){try{var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');if(d&&d.set){d.set.call(el,v);}else{el.value=v;}}catch(x){el.value=v;}
            el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));el.dispatchEvent(new Event('blur',{bubbles:true}));}
          function vis(el){return !!el&&el.offsetParent!==null&&!el.disabled;}
          function q(s){for(var i=0;i<s.length;i++){var el=document.querySelector(s[i]);if(vis(el))return el;}return null;}
          var em=q(['input[type=email]','input[autocomplete=username]','input[name*=email i]','input[name=username]','input[id*=email i]','input[id*=user i]']);
          var pw=q(['input[type=password]','input[autocomplete=current-password]','input[name*=password i]','input[id*=password i]']);
          if(em&&EM&&!em.value)setVal(em,EM);
          if(pw&&PW)setVal(pw,PW);
          function submit(){var b=q(['button[type=submit]','input[type=submit]']);
            if(!b){var bs=document.querySelectorAll('button,a.btn,input[type=button],a[role=button]');
              for(var i=0;i<bs.length;i++){var t=((bs[i].innerText||bs[i].value||'')+'').toLowerCase();
                if(/sign ?in|log ?in|einloggen|anmelden|weiter|next|continue|submit|confirm/.test(t)&&vis(bs[i])){b=bs[i];break;}}}
            if(b)b.click();}
          if(pw&&PW){setTimeout(submit,500);}else if(em&&EM){setTimeout(submit,500);}
        }catch(err){}})();
    """.trimIndent().replace("\n", " ")
    return "javascript:" + js.replace("__E__", esc(email)).replace("__P__", esc(password))
}

