package tribixbite.cleverkeys.ui.settings.sections

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import tribixbite.cleverkeys.R
import tribixbite.cleverkeys.SettingsActivity
import tribixbite.cleverkeys.ui.settings.CollapsibleSettingsSection
import tribixbite.cleverkeys.ui.settings.SettingsSwitch
import tribixbite.cleverkeys.ui.settings.saveSetting

/**
 * T4: Voice input section. The feature is OFF by default; enabling it requests
 * the RECORD_AUDIO runtime permission (denial reverts the toggle) and reveals
 * the server URL field. Nothing here affects the keyboard until the user
 * explicitly turns the toggle on.
 */
@Composable
internal fun SettingsActivity.VoiceSection() {
    CollapsibleSettingsSection(
        title = stringResource(R.string.settings_section_voice),
        expanded = voiceSectionExpanded,
        onExpandChange = { voiceSectionExpanded = it }
    ) {
        SettingsSwitch(
            title = stringResource(R.string.voice_input_enabled_title),
            description = stringResource(R.string.voice_input_enabled_desc),
            checked = voiceInputEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    // Persist immediately; if the runtime request is denied, the
                    // launcher callback (SettingsActivity) reverts both.
                    voiceInputEnabled = true
                    saveSetting("voice_input_enabled", true)
                    val activity = this@VoiceSection
                    if (ContextCompat.checkSelfPermission(
                            activity, Manifest.permission.RECORD_AUDIO
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        activity.voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    voiceInputEnabled = false
                    saveSetting("voice_input_enabled", false)
                }
            }
        )

        if (voiceInputEnabled) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.voice_server_url_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.voice_server_url_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = voiceServerUrl,
                    onValueChange = { voiceServerUrl = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        saveSetting("voice_server_url", voiceServerUrl.trim())
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(R.string.voice_server_url_title))
                }
            }
        }
    }
}
