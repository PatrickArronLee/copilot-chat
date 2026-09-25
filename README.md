# Copilot Chat for Android

Copilot Chat is a native Android app for agentic chat with GitHub Copilot. It provides a Compose-based chat experience that streams responses, retains conversations on-device, and lets a model use local tools to work with files and shell commands on the device.

## What it does

- Chats with the GitHub Copilot Chat Completions API using a selected Copilot model.
- Streams assistant output and displays tool calls and their results as they happen.
- Runs an agent loop for up to 10 tool-call iterations, so the model can inspect context, act, and continue its answer.
- Supports local `bash`, `read_file`, `write_file`, `list_files`, `search_files`, `append_file`, and `http_get` tools in direct mode.
- Stores conversations and settings locally, with controls for model selection, voice input, copying, sharing, and conversation management.
- Falls back to ordinary streamed chat when the selected model or token does not support tool calling.

## Requirements

- Android Studio with the Android SDK for API 34.
- JDK 17.
- An Android device or emulator running Android 8.0 (API 26) or later.
- A GitHub Copilot token configured in the app's settings.

## Build and run

1. Open the project in Android Studio.
2. Let Gradle sync the project.
3. Choose an Android device or emulator.
4. Run the `app` configuration.

From a terminal with the Android SDK configured, build a debug APK with:

```bash
./gradlew assembleDebug
```

On Windows, use:

```bat
gradlew.bat assembleDebug
```

## Agent execution modes

### Direct mode

The app calls the Copilot API directly and executes tools in its own Android environment. Shell support is intended for Termux or similar Android Linux environments. Tool calls can read and write files and execute commands available to the app, so use a token and device environment you trust.

### Bridge mode

For a fuller Linux environment, run `bridge.py` in UserLAnd or Termux. The bridge reads a Copilot token from its command-line argument, `COPILOT_TOKEN`, or supported Copilot CLI configuration files, then proxies requests to the Copilot API and executes approved tool calls in that environment.

Start it manually:

```bash
python3 bridge.py --token YOUR_GITHUB_TOKEN
```

Or use the helper script, which downloads the bridge from the `main` branch:

```bash
bash run-bridge.sh YOUR_GITHUB_TOKEN
```

Configure the bridge address in the app settings after it is running.

## Project layout

- `app/` - Android app, including Compose UI, conversation storage, settings, and the Copilot API client.
- `bridge.py` - Optional Python bridge for running agent tools in a Linux environment.
- `run-bridge.sh` - Helper for downloading and starting the bridge.

## Security note

Agentic tool calling can execute commands and modify files. Review prompts and tool activity, keep tokens private, and do not expose the optional bridge to untrusted networks.