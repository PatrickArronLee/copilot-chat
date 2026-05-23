package com.patrick.copilotchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.patrick.copilotchat.api.CopilotApiClient
import com.patrick.copilotchat.data.AppPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: AppPreferences,
    onBack: () -> Unit
) {
    var token by remember { mutableStateOf(prefs.githubToken) }
    var selectedModel by remember { mutableStateOf(prefs.selectedModel) }
    var systemPrompt by remember { mutableStateOf(prefs.systemPrompt) }
    var showToken by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        prefs.githubToken = token
                        prefs.selectedModel = selectedModel
                        prefs.systemPrompt = systemPrompt
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Token section
            Text("GitHub Token", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GitHub Copilot Token") },
                placeholder = { Text("gho_... or ghp_...") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showToken) "Hide" else "Show")
                    }
                },
                supportingText = {
                    Text("Use your existing Copilot token (gho_) or a PAT from github.com/settings/tokens")
                }
            )

            HorizontalDivider()

            // Model section
            Text("AI Model", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            ExposedDropdownMenuBox(
                expanded = showModelDropdown,
                onExpandedChange = { showModelDropdown = it }
            ) {
                OutlinedTextField(
                    value = CopilotApiClient.AVAILABLE_MODELS.find { it.first == selectedModel }?.second ?: selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showModelDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = showModelDropdown,
                    onDismissRequest = { showModelDropdown = false }
                ) {
                    CopilotApiClient.AVAILABLE_MODELS.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { selectedModel = id; showModelDropdown = false }
                        )
                    }
                }
            }

            HorizontalDivider()

            // System prompt
            Text("System Prompt", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Instructions for the AI") },
                minLines = 3,
                maxLines = 6
            )

            HorizontalDivider()

            Button(
                onClick = {
                    prefs.githubToken = token
                    prefs.selectedModel = selectedModel
                    prefs.systemPrompt = systemPrompt
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "Saved ✓" else "Save Settings")
            }

            if (!token.startsWith("gh")) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )) {
                    Column(modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Where to find your token", style = MaterialTheme.typography.labelMedium)
                        Text("Option 1 — Copilot CLI token (easiest):", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("  Run: cat ~/.copilot/config.json", style = MaterialTheme.typography.bodySmall)
                        Text("  Copy the gho_... value", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Option 2 — New PAT:", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("  github.com/settings/tokens → Generate new token (classic) → repo scope", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
