package com.yani.client.ui.profile

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yani.client.BuildConfig
import com.yani.client.R
import com.yani.client.data.knownPlayers
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.appContainer
import com.yani.client.ui.common.focusBorder
import com.yani.client.util.AppLanguage

private fun restartApp(activity: Activity?) {
    activity ?: return
    activity.startActivity(Intent.makeRestartActivityTask(activity.componentName))
    activity.finish()
}

@Composable
fun SettingsScreen() {
    val prefs = appContainer().prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val selectedLanguage = remember { AppLanguage.selected() }
    var appToken by rememberSaveable { mutableStateOf(settings.appToken) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.settings))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Header(stringResource(R.string.settings_language))
            val languages = listOf(
                null to stringResource(R.string.language_system),
                "en" to "English",
                "ru" to "Русский",
            )
            languages.forEach { (tag, label) ->
                RadioRow(label, selected = selectedLanguage == tag) {
                    if (selectedLanguage != tag) {
                        AppLanguage.select(tag)
                        restartApp(activity)
                    }
                }
            }

            Header(stringResource(R.string.settings_player))
            Text(
                stringResource(R.string.settings_player_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RadioRow(stringResource(R.string.player_auto), settings.preferredPlayer.isEmpty()) {
                prefs.updateSettings { it.copy(preferredPlayer = "") }
            }
            knownPlayers.forEach { key ->
                RadioRow(key.replaceFirstChar { it.uppercase() }.let { if (key == "cvh") "CVH" else it }, settings.preferredPlayer == key) {
                    prefs.updateSettings { it.copy(preferredPlayer = key) }
                }
            }
            SwitchRow(stringResource(R.string.settings_auto_next), settings.autoNext) { v -> prefs.updateSettings { it.copy(autoNext = v) } }
            SwitchRow(stringResource(R.string.settings_auto_skip), settings.autoSkipOpening) { v ->
                prefs.updateSettings { it.copy(autoSkipOpening = v) }
            }

            Header(stringResource(R.string.settings_api))
            Text(
                stringResource(R.string.settings_app_token_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = appToken,
                onValueChange = { appToken = it.trim() },
                label = { Text("X-Application") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                YButton(stringResource(R.string.save), onClick = { prefs.updateSettings { it.copy(appToken = appToken) } }, enabled = appToken != settings.appToken)
                YButton(stringResource(R.string.get_token), onClick = { nav.openUrl("https://yummyani.me/dev/applications") }, primary = false)
            }

            Header(stringResource(R.string.settings_about))
            Text(
                stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusBorder(source, shape, scale = 1.01f)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton, interactionSource = source, indication = null)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusBorder(source, shape, scale = 1.01f)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch, interactionSource = source, indication = null)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
