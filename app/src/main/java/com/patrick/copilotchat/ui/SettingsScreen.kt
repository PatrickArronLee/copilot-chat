package com.patrick.copilotchat.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.patrick.copilotchat.api.CopilotApiClient
import com.patrick.copilotchat.data.AppPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: AppPreferences,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val api = remember { CopilotApiClient() }

    var token by remember { mutableStateOf(prefs.githubToken) }
    var selectedModel by remember { mutableStateOf(prefs.selectedModel) }
    var systemPrompt by remember { mutableStateOf(prefs.systemPrompt) }
    var bridgeUrl by remember { mutableStateOf(prefs.bridgeUrl) }
    var toolsEnabled by remember { mutableStateOf(prefs.toolsEnabled) }
    var showToken by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf("") }
    var bridgeStatus by remember { mutableStateOf("") } // "", "checking", "online", "offline"
    var displayModels by remember { mutableStateOf(prefs.supportedModels) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        prefs.githubToken = token
                        prefs.selectedModel = selectedModel
                        prefs.systemPrompt = systemPrompt
                        prefs.bridgeUrl = bridgeUrl
                        prefs.toolsEnabled = toolsEnabled
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
            // ── Bridge Server ─────────────────────────────────────────────
            Text("Bridge Server (Recommended)", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("The bridge server runs in your UserLAnd terminal and enables:",
                        style = MaterialTheme.typography.bodySmall)
                    Text("• Auto-reads your token (no manual entry needed)",
                        style = MaterialTheme.typography.bodySmall)
                    Text("• bash, file read/write, list tools",
                        style = MaterialTheme.typography.bodySmall)
                    Text("• Full agentic loop like the Copilot CLI",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Start it: python3 ~/copilot-bridge/bridge.py",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            OutlinedTextField(
                value = bridgeUrl,
                onValueChange = { bridgeUrl = it; bridgeStatus = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bridge URL") },
                placeholder = { Text("http://localhost:8765") },
                singleLine = true,
                supportingText = {
                    when (bridgeStatus) {
                        "online"   -> Text("🟢 Bridge is online", color = MaterialTheme.colorScheme.primary)
                        "offline"  -> Text("🔴 Not reachable — start the bridge in UserLAnd",
                            color = MaterialTheme.colorScheme.error)
                        "checking" -> Text("Checking…")
                        else -> Text("Default: http://localhost:8765")
                    }
                }
            )

            Button(
                onClick = {
                    bridgeStatus = "checking"
                    prefs.bridgeUrl = bridgeUrl
                    scope.launch {
                        val available = api.isBridgeAvailable(bridgeUrl)
                        bridgeStatus = if (available) "online" else "offline"
                        viewModel.checkBridge()
                        if (available) {
                            // Refresh model list via bridge
                            val models = api.fetchAvailableModels(prefs.githubToken.takeIf { it.isNotBlank() } ?: "")
                            if (models != null) {
                                prefs.tokenModelIds = models
                                displayModels = prefs.supportedModels
                                if (displayModels.none { it.first == selectedModel }) {
                                    selectedModel = displayModels.firstOrNull()?.first ?: "gpt-4o"
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = bridgeStatus != "checking"
            ) {
                Text(if (bridgeStatus == "checking") "Checking…" else "Test Bridge Connection")
            }

            // ─── Tools section ───────────────────────────────────────────────────
            HorizontalDivider()
            Text(
                "Tools",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable tool calling", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "bash, file ops, HTTP (requires compatible token)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = toolsEnabled,
                    onCheckedChange = {
                        toolsEnabled = it
                        prefs.toolsEnabled = it
                    }
                )
            }

            HorizontalDivider()

            // ── Token (fallback when bridge offline) ─────────────────────
            Text("GitHub Token (Fallback)", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text("Used when the bridge server is not running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = token,
                onValueChange = { token = it; saveStatus = "" },
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
                supportingText = { Text("cat ~/.copilot/config.json → copilotTokens value") }
            )

            HorizontalDivider()

            // ── Model ─────────────────────────────────────────────────────
            Text("AI Model", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            ExposedDropdownMenuBox(
                expanded = showModelDropdown,
                onExpandedChange = { showModelDropdown = it }
            ) {
                OutlinedTextField(
                    value = displayModels.find { it.first == selectedModel }?.second ?: selectedModel,
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
                    displayModels.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { selectedModel = id; showModelDropdown = false }
                        )
                    }
                }
            }

            HorizontalDivider()

            // ── System Prompt ─────────────────────────────────────────────
            val systemPromptPresets = listOf(
                "Default" to "You are a helpful AI assistant. Be concise and friendly.",
                "Coding" to "You are an expert software engineer. Give concise, correct code. Prefer showing code over explaining it.",
                "Concise" to "Answer in 1-3 sentences maximum. Be direct.",
                "Android/Termux" to "You are an AI assistant running on Android via Termux. You have bash access. Be concise and technical."
            )
            var showPresets by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System Prompt", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showPresets = !showPresets }) {
                    Text(if (showPresets) "Hide presets" else "Presets ▼")
                }
            }
            if (showPresets) {
                systemPromptPresets.forEach { (label, prompt) ->
                    TextButton(
                        onClick = { systemPrompt = prompt; showPresets = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
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
                    saveStatus = "saving"
                    prefs.githubToken = token
                    prefs.bridgeUrl = bridgeUrl
                    prefs.systemPrompt = systemPrompt
                    scope.launch {
                        val models = api.fetchAvailableModels(token.trim())
                        if (models != null) {
                            prefs.tokenModelIds = models
                            displayModels = prefs.supportedModels
                            if (displayModels.none { it.first == selectedModel }) {
                                selectedModel = displayModels.firstOrNull()?.first ?: "gpt-4o"
                            }
                        }
                        prefs.selectedModel = selectedModel
                        saveStatus = if (models != null) "saved" else "error"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = saveStatus != "saving"
            ) {
                Text(when (saveStatus) {
                    "saving" -> "Saving…"
                    "saved"  -> "Saved ✓ (${displayModels.size} models)"
                    "error"  -> "Saved (token check failed)"
                    else     -> "Save Settings"
                })
            }
        }
    }
}
