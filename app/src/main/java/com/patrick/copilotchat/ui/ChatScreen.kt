package com.patrick.copilotchat.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrick.copilotchat.api.CopilotApiClient
import com.patrick.copilotchat.data.Conversation
import com.patrick.copilotchat.data.Message
import com.patrick.copilotchat.data.MessageRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onSettingsClick: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val activeConversation by viewModel.activeConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var inputText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<Message?>(null) }
    var copiedMessageId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?: return@rememberLauncherForActivityResult
            inputText = if (inputText.isBlank()) recognized else "$inputText $recognized"
        }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun submitMessage() {
        val text = inputText.trim()
        if (text.isNotEmpty() && !isLoading) {
            viewModel.sendMessage(text)
            inputText = ""
        }
    }

    fun shareText(text: String, subject: String, chooserTitle: String) {
        if (text.isBlank()) return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    fun copyMessage(message: Message) {
        clipboardManager.setText(AnnotatedString(message.content))
        copiedMessageId = message.id
    }

    fun openVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
        }
        voiceLauncher.launch(intent)
    }

    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(copiedMessageId) {
        if (copiedMessageId != null) {
            delay(2000)
            copiedMessageId = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    conversations = conversations.sortedByDescending { it.createdAt },
                    activeConversationId = activeConversation?.id,
                    onNewChat = {
                        viewModel.newConversation()
                        closeDrawer()
                    },
                    onConversationClick = { id ->
                        viewModel.switchConversation(id)
                        closeDrawer()
                    },
                    onConversationDelete = viewModel::deleteConversation
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Copilot AI", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = { showModelPicker = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = modelShortLabel(currentModel),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (messages.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearActiveMessages) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                            }
                            IconButton(
                                onClick = {
                                    shareText(
                                        text = viewModel.getShareText(),
                                        subject = "Copilot Chat",
                                        chooserTitle = "Share conversation"
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share conversation")
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                InputBar(
                    value = inputText,
                    onValueChange = { inputText = it },
                    onSend = ::submitMessage,
                    onStop = viewModel::cancelStreaming,
                    onVoiceInput = ::openVoiceInput,
                    isLoading = isLoading
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                error?.let {
                    ErrorBanner(
                        message = it,
                        onDismiss = viewModel::dismissError
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (messages.isEmpty()) {
                        WelcomeScreen()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                MessageBubble(
                                    message = message,
                                    isLastAssistant = !isLoading &&
                                        message.role == MessageRole.ASSISTANT &&
                                        message.id == lastAssistantId,
                                    onRegenerate = viewModel::regenerateLastResponse,
                                    onLongPress = { actionMessage = message },
                                    isCopied = copiedMessageId == message.id
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            sheetState = modelSheetState
        ) {
            ModelPickerSheet(
                currentModel = currentModel,
                onModelSelected = { modelId ->
                    viewModel.setModel(modelId)
                    showModelPicker = false
                }
            )
        }
    }

    actionMessage?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { actionMessage = null },
            sheetState = actionSheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    headlineContent = { Text("Copy message") },
                    leadingContent = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        copyMessage(message)
                        actionMessage = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Share message") },
                    leadingContent = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        shareText(message.content, "Copilot Chat", "Share message")
                        actionMessage = null
                    }
                )
                if (message.role == MessageRole.ASSISTANT && !isLoading) {
                    ListItem(
                        headlineContent = { Text("Regenerate") },
                        leadingContent = {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            viewModel.regenerateLastResponse()
                            actionMessage = null
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerContent(
    conversations: List<Conversation>,
    activeConversationId: String?,
    onNewChat: () -> Unit,
    onConversationClick: (String) -> Unit,
    onConversationDelete: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 280.dp, max = 320.dp)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Copilot AI", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(12.dp))

        androidx.compose.material3.Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("New Chat")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(conversations, key = { it.id }) { conversation ->
                val isActive = conversation.id == activeConversationId
                Surface(
                    color = if (isActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onConversationClick(conversation.id) },
                            onLongClick = { onConversationDelete(conversation.id) }
                        )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = conversation.title.ifBlank { "New Chat" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = modelShortLabel(conversation.modelId),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Dismiss",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceInput: () -> Unit,
    isLoading: Boolean
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            FilledIconButton(
                onClick = onVoiceInput,
                enabled = !isLoading,
                modifier = Modifier.size(48.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Voice input")
            }
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = !isLoading,
                placeholder = { Text("Message Copilot...") },
                shape = RoundedCornerShape(26.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (!isLoading) onSend()
                })
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop generation")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Send")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelPickerSheet(
    currentModel: String,
    onModelSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Choose model",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        CopilotApiClient.AVAILABLE_MODELS.forEach { (id, name) ->
            ListItem(
                headlineContent = { Text(name) },
                trailingContent = {
                    if (id == currentModel) {
                        Icon(Icons.Default.Check, contentDescription = "Selected")
                    }
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onModelSelected(id) },
                    onLongClick = { onModelSelected(id) }
                )
            )
        }
    }
}

@Composable
private fun WelcomeScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("👾", fontSize = 64.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Copilot AI", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Send a message to start",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isLastAssistant: Boolean,
    onRegenerate: () -> Unit,
    onLongPress: () -> Unit,
    isCopied: Boolean
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 20.dp else 8.dp,
                    topEnd = if (isUser) 8.dp else 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp
                ),
                color = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
            ) {
                when {
                    message.isLoading && message.content.isBlank() -> {
                        TypingIndicator(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }

                    isUser -> {
                        Text(
                            text = message.content,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    else -> {
                        MarkdownText(
                            text = message.content,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isLastAssistant) {
                IconButton(
                    onClick = onRegenerate,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Regenerate response",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isCopied) {
                Text(
                    text = "Copied!",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

private fun modelShortLabel(modelId: String): String {
    return CopilotApiClient.AVAILABLE_MODELS
        .firstOrNull { it.first == modelId }
        ?.second
        ?.substringBefore(" (")
        ?: modelId
}
