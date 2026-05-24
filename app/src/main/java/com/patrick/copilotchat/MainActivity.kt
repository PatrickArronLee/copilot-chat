package com.patrick.copilotchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.patrick.copilotchat.data.AppPreferences
import com.patrick.copilotchat.data.ConversationRepository
import com.patrick.copilotchat.ui.ChatScreen
import com.patrick.copilotchat.ui.ChatViewModel
import com.patrick.copilotchat.ui.SettingsScreen
import com.patrick.copilotchat.ui.theme.CopilotChatTheme

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var repo: ConversationRepository
    private lateinit var viewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = AppPreferences(applicationContext)
        repo = ConversationRepository(applicationContext)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(prefs, repo) as T
        })[ChatViewModel::class.java]

        setContent {
            CopilotChatTheme {
                var showSettings by remember { mutableStateOf(!prefs.isConfigured && prefs.bridgeUrl.contains("localhost")) }

                if (showSettings) {
                    SettingsScreen(
                        prefs = prefs,
                        viewModel = viewModel,
                        onBack = { showSettings = false }
                    )
                } else {
                    ChatScreen(
                        viewModel = viewModel,
                        onSettingsClick = { showSettings = true }
                    )
                }
            }
        }
    }
}
