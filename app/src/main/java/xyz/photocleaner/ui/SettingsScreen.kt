package xyz.photocleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.photocleaner.data.CleanupMode
import xyz.photocleaner.vm.AppViewModel

@Composable
fun SettingsScreen(appVm: AppViewModel, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val mode by appVm.mode.collectAsState()
    val albumName by appVm.albumName.collectAsState()
    val appLock by appVm.appLock.collectAsState()
    val skipDecided by appVm.skipDecided.collectAsState()
    val includeArchived by appVm.includeArchived.collectAsState()
    val allowScreenshots by appVm.allowScreenshots.collectAsState()
    val kept by appVm.keptCount.collectAsState()

    var albumDraft by remember(albumName) { mutableStateOf(albumName) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("When you swipe delete")
        Card {
            ModeRow(
                selected = mode == CleanupMode.TRASH,
                title = "Move to Google Photos trash",
                subtitle = "Really deletes it. Recoverable for 60 days, then the storage is freed.",
                onClick = { appVm.setMode(CleanupMode.TRASH) },
            )
            ModeRow(
                selected = mode == CleanupMode.ALBUM,
                title = "Collect in an album instead",
                subtitle = "Deletes nothing. Gathers them so you can check before deleting yourself.",
                onClick = { appVm.setMode(CleanupMode.ALBUM) },
            )
            if (mode == CleanupMode.ALBUM) {
                OutlinedTextField(
                    value = albumDraft,
                    onValueChange = { albumDraft = it },
                    label = { Text("Album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
                if (albumDraft != albumName && albumDraft.isNotBlank()) {
                    TextButton(
                        onClick = { appVm.setAlbumName(albumDraft.trim()) },
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                    ) { Text("Save album name") }
                }
            }
        }

        SectionTitle("Reviewing")
        Card {
            ToggleRow(
                title = "Skip photos I've already judged",
                subtitle = "Re-opening a month shows only what you haven't seen.",
                checked = skipDecided,
                onChange = appVm::setSkipDecided,
            )
            ToggleRow(
                title = "Include archived photos",
                subtitle = "Also review items you've archived in Google Photos.",
                checked = includeArchived,
                onChange = appVm::setIncludeArchived,
            )
        }

        SectionTitle("Security")
        Card {
            ToggleRow(
                title = "Require unlock to open",
                subtitle = "Ask for fingerprint, face or device PIN each time.",
                checked = appLock,
                onChange = appVm::setAppLock,
            )
            ToggleRow(
                title = "Allow screenshots",
                subtitle = "Off by default, which is why screenshots come out blank. " +
                    "Turning it on also lets your photos appear in the app switcher.",
                checked = allowScreenshots,
                onChange = appVm::setAllowScreenshots,
            )
            InfoRow(
                "Where your data lives",
                "Verdicts are stored in an encrypted database on this phone, with the key " +
                    "held in the Android Keystore. There is no server and no account — " +
                    "nothing is uploaded anywhere except the delete requests you confirm.",
            )
        }

        SectionTitle("Data")
        Card {
            InfoRow("Photos kept", "$kept marked as keep on this device")
            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) {
                    Text("Clear my verdicts")
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                OutlinedButton(
                    onClick = { confirmWipe = true },
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Sign out and erase everything") }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Photo Cleaner talks to Google Photos through its own web interface, using " +
                "undocumented endpoints. Google can change them at any time, which would " +
                "stop the app working until it is updated.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all verdicts?") },
            text = {
                Text(
                    "Forgets every keep/delete decision stored on this phone. Your photos " +
                        "in Google Photos are not touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; appVm.clearDecisions() }) {
                    Text("Clear")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Sign out and erase everything?") },
            text = {
                Text(
                    "Signs out of Google, deletes the encrypted database and its key, and " +
                        "clears all cookies from this app. Your Google Photos library is " +
                        "not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    appVm.signOutAndWipe(onSignedOut)
                }) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
