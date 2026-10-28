package com.yani.client.ui.profile

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.data.AuthManager
import com.yani.client.data.Prefs
import com.yani.client.data.Profile
import com.yani.client.data.YummyApi
import com.yani.client.data.absUrl
import com.yani.client.ui.Routes
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.focusBorder
import com.yani.client.util.currentLocale
import com.yani.client.util.formatDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(private val api: YummyApi, private val prefs: Prefs, private val auth: AuthManager) : ViewModel() {
    val session = prefs.session
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile = _profile.asStateFlow()

    fun refresh() {
        if (prefs.session.value == null) {
            _profile.value = null
            return
        }
        viewModelScope.launch {
            runCatching { api.profile() }.onSuccess {
                _profile.value = it
                prefs.updateProfile(it.nickname, absUrl(it.avatars?.big ?: it.avatars?.small))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            auth.logout()
            _profile.value = null
        }
    }
}

@Composable
fun ProfileScreen() {
    val vm = appViewModel { ProfileViewModel(it.api, it.prefs, it.auth) }
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val locale = currentLocale()
    var confirmLogout by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(session?.userId) { vm.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        val s = session
        if (s == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.AccountCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.not_logged_in), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                YButton(stringResource(R.string.login), onClick = { nav.openRoute(Routes.LOGIN) })
            }
        } else {
            Box(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = absUrl(profile?.banner?.cropped),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.5f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3.5f),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(ScreenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = absUrl(profile?.avatars?.big) ?: s.avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(profile?.nickname ?: s.nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        profile?.registerDate?.let {
                            Text(
                                stringResource(R.string.registered, formatDate(it, locale)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .widthIn(max = 640.dp)
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            if (s != null) {
                MenuRow(Icons.Filled.Notifications, stringResource(R.string.notifications), badge = profile?.notifications?.count ?: 0) {
                    nav.openRoute(Routes.NOTIFICATIONS)
                }
                MenuRow(Icons.Filled.History, stringResource(R.string.history)) { nav.openRoute(Routes.HISTORY) }
            }
            MenuRow(Icons.Filled.Collections, stringResource(R.string.collections)) { nav.openRoute(Routes.COLLECTIONS) }
            MenuRow(Icons.Filled.Settings, stringResource(R.string.settings)) { nav.openRoute(Routes.SETTINGS) }
            MenuRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_website)) {
                nav.openUrl(if (s != null) "https://yummyani.me/users/id${s.userId}" else "https://yummyani.me/")
            }
            if (s != null) {
                MenuRow(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.logout)) { confirmLogout = true }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logout()
                }) { Text(stringResource(R.string.logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun MenuRow(icon: ImageVector, text: String, badge: Int = 0, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        shape = shape,
        interactionSource = source,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .focusBorder(source, shape, scale = 1.02f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (badge > 0) Badge { Text(badge.toString()) }
        }
    }
}
