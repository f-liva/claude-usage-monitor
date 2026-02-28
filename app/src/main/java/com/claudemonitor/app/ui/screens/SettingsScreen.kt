package com.claudemonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudemonitor.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    refreshIntervalMinutes: Int,
    notificationEnabled: Boolean,
    accountEmail: String?,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    onRefreshIntervalChange: (Int) -> Unit,
    onNotificationToggle: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account section
            Text(
                stringResource(R.string.account),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            accountEmail ?: stringResource(R.string.default_account_name),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Monitoring section
            Text(
                stringResource(R.string.monitoring),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    // Notification toggle
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.persistent_notification)) },
                        supportingContent = { Text(stringResource(R.string.persistent_notification_desc)) },
                        leadingContent = {
                            Icon(Icons.Rounded.Notifications, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = notificationEnabled,
                                onCheckedChange = onNotificationToggle
                            )
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // Refresh interval
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.refresh_interval)) },
                        supportingContent = { Text(stringResource(R.string.every_n_minutes, refreshIntervalMinutes)) },
                        leadingContent = {
                            Icon(Icons.Rounded.Timer, contentDescription = null)
                        }
                    )

                    // Interval slider
                    Slider(
                        value = refreshIntervalMinutes.toFloat(),
                        onValueChange = { onRefreshIntervalChange(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 10,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // Language
                    val languageLabel = when (currentLanguage) {
                        "en" -> stringResource(R.string.language_en)
                        "it" -> stringResource(R.string.language_it)
                        else -> stringResource(R.string.language_system)
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.language)) },
                        supportingContent = { Text(languageLabel) },
                        leadingContent = {
                            Icon(Icons.Rounded.Language, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )

                    // Language options
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguageChip(
                            label = stringResource(R.string.language_system),
                            selected = currentLanguage == "system",
                            onClick = { onLanguageChange("system") },
                            modifier = Modifier.weight(1f)
                        )
                        LanguageChip(
                            label = stringResource(R.string.language_en),
                            selected = currentLanguage == "en",
                            onClick = { onLanguageChange("en") },
                            modifier = Modifier.weight(1f)
                        )
                        LanguageChip(
                            label = stringResource(R.string.language_it),
                            selected = currentLanguage == "it",
                            onClick = { onLanguageChange("it") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Danger zone
            Text(
                stringResource(R.string.account),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.logout_button))
            }

            // App info
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.app_version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                stringResource(R.string.app_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.logout_dialog_title)) },
            text = { Text(stringResource(R.string.logout_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.logout_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun LanguageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
