package com.yani.client.ui.profile

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yani.client.R
import com.yani.client.data.ApiException
import com.yani.client.data.AuthManager
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.errorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val loading: Boolean = false,
    val error: Throwable? = null,
    val needCaptcha: Boolean = false,
    val success: Boolean = false,
)

class LoginViewModel(private val auth: AuthManager) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun login(login: String, password: String, captcha: String? = null) {
        if (login.isBlank() || password.isEmpty() || _state.value.loading) return
        viewModelScope.launch {
            _state.value = LoginState(loading = true)
            try {
                auth.login(login, password, captcha)
                _state.value = LoginState(success = true)
            } catch (e: ApiException) {
                _state.value = LoginState(error = e.takeUnless { it.needsCaptcha }, needCaptcha = e.needsCaptcha)
            } catch (e: Exception) {
                _state.value = LoginState(error = e)
            }
        }
    }

    fun dismissCaptcha() = _state.update { it.copy(needCaptcha = false) }
}

@Composable
fun LoginScreen() {
    val vm = appViewModel { LoginViewModel(it.auth) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.success) { if (state.success) nav.back() }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.login))
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    label = { Text(stringResource(R.string.login_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.login(login, password) }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    val text = if (it is ApiException && it.isUnauthorized) stringResource(R.string.wrong_credentials) else errorMessage(it)
                    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                } else {
                    YButton(stringResource(R.string.login), onClick = { vm.login(login, password) }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { nav.openUrl("https://yummyani.me/") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.register_on_site))
                }
            }
        }
    }

    if (state.needCaptcha) {
        CaptchaDialog(
            onToken = { token -> vm.login(login, password, token) },
            onDismiss = vm::dismissCaptcha,
        )
    }
}

private const val HCAPTCHA_SITE_KEY = "b1847961-208e-4a90-9671-1e6bba9e0b36"

private val captchaHtml = """
    <!DOCTYPE html><html><head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
    <style>body{margin:0;padding:16px;background:#16161d;display:flex;justify-content:center}</style>
    </head><body>
    <div class="h-captcha" data-sitekey="$HCAPTCHA_SITE_KEY" data-theme="dark" data-callback="onPass"></div>
    <script>function onPass(t){ YummyCaptcha.onToken(t); }</script>
    </body></html>
""".trimIndent()

/** The API answers 420 after several attempts; the site key belongs to yummyani.me, so the page is hosted there. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaDialog(onToken: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.captcha_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            val main = Handler(Looper.getMainLooper())
                            webViewClient = object : WebViewClient() {
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    main.post(onDismiss)
                                    return true
                                }
                            }
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onToken(token: String) {
                                    main.post { onToken(token) }
                                }
                            }, "YummyCaptcha")
                            loadDataWithBaseURL("https://yummyani.me/", captchaHtml, "text/html", "utf-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
