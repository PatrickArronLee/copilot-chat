#!/usr/bin/env python3
"""
Copilot Chat Bridge Server
Runs in UserLAnd/Termux. Reads auth from Copilot CLI config automatically.
Proxies requests to GitHub Copilot API with full agentic tool calling.

Usage: python3 bridge.py [port]  (default port: 8765)
"""

import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 8765
CLI_TOKEN_ARG = next((sys.argv[i+1] for i, a in enumerate(sys.argv) if a == "--token" and i+1 < len(sys.argv)), None)

CONFIG_PATHS = [
    os.path.expanduser("~/.copilot/config.json"),           # UserLAnd / Copilot CLI
    "/root/.copilot/config.json",                           # explicit root
    os.path.expanduser("~/.config/github-copilot/hosts.json"),  # older CLI versions
]

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "bash",
            "description": (
                "Execute a shell command in the UserLAnd/Linux environment. "
                "Use this for file operations, running programs, checking system state, etc."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {
                        "type": "string",
                        "description": "The bash command to execute"
                    }
                },
                "required": ["command"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the full contents of a file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Absolute or ~ path to read"}
                },
                "required": ["path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Write content to a file, creating it and parent directories if needed.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Path to write"},
                    "content": {"type": "string", "description": "Content to write"}
                },
                "required": ["path", "content"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List files and directories at a path.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Directory path to list"}
                },
                "required": ["path"]
            }
        }
    }
]


def strip_js_comments(text):
    """Remove // line comments (used in Copilot CLI's config.json)."""
    lines = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("//"):
            continue
        lines.append(line)
    return "\n".join(lines)


def get_token():
    """Return token: CLI arg > env var > config file."""
    if CLI_TOKEN_ARG:
        return CLI_TOKEN_ARG
    env_tok = os.environ.get("COPILOT_TOKEN", "").strip()
    if env_tok:
        return env_tok
    for path in CONFIG_PATHS:
        try:
            with open(path) as f:
                text = f.read()
            data = json.loads(strip_js_comments(text))
            tokens = data.get("copilotTokens", {}) or data.get("github.com", {})
            if isinstance(tokens, dict):
                val = list(tokens.values())[0] if tokens else None
                if val and isinstance(val, str):
                    return val
                # hosts.json format: {"github.com": {"oauth_token": "..."}}
                oauth = tokens.get("oauth_token", "")
                if oauth:
                    return oauth
        except Exception:
            continue
    return None


def execute_tool(name, args):
    """Execute a tool call. Returns (output_str, is_error)."""
    try:
        if name == "bash":
            cmd = args.get("command", "")
            result = subprocess.run(
                cmd,
                shell=True,
                capture_output=True,
                text=True,
                timeout=60,
                cwd=os.path.expanduser("~")
            )
            output = result.stdout
            if result.stderr.strip():
                output += ("\n" if output else "") + "STDERR:\n" + result.stderr
            if result.returncode != 0:
                output += f"\n[exit code {result.returncode}]"
            return (output.strip() or "(no output)"), result.returncode != 0

        elif name == "read_file":
            path = os.path.expanduser(args.get("path", ""))
            with open(path) as f:
                return f.read(), False

        elif name == "write_file":
            path = os.path.expanduser(args.get("path", ""))
            parent = os.path.dirname(path)
            if parent:
                os.makedirs(parent, exist_ok=True)
            with open(path, "w") as f:
                f.write(args.get("content", ""))
            return f"Written {len(args.get('content',''))} bytes to {path}", False

        elif name == "list_files":
            path = os.path.expanduser(args.get("path", "."))
            entries = sorted(os.listdir(path))
            lines = []
            for e in entries:
                full = os.path.join(path, e)
                suffix = "/" if os.path.isdir(full) else ""
                lines.append(e + suffix)
            return "\n".join(lines) if lines else "(empty)", False

        else:
            return f"Unknown tool: {name}", True

    except Exception as e:
        return f"Error: {e}", True


def copilot_request_json(token, model, messages, use_tools=True):
    """Call Copilot chat completions (non-streaming). Returns parsed JSON or raises."""
    payload = {
        "model": model,
        "messages": messages,
        "stream": False,
        "max_tokens": 4096
    }
    if use_tools and not is_reasoning_model(model):
        payload["tools"] = TOOLS
        payload["tool_choice"] = "auto"

    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        "https://api.githubcopilot.com/chat/completions",
        data=data,
        headers={
            "Authorization": f"Bearer {token.strip()}",
            "Content-Type": "application/json",
            "Copilot-Integration-Id": "vscode-chat",
            "Editor-Version": "vscode/1.85.0"
        },
        method="POST"
    )
    resp = urllib.request.urlopen(req, timeout=120)
    return json.loads(resp.read().decode())


def is_reasoning_model(model):
    return model.startswith("o1") or model.startswith("o3") or model.startswith("o4")


def run_agent_loop(token, model, messages, system_prompt, send_event):
    """
    Runs the agentic loop:
    1. Call Copilot API
    2. If finish_reason = tool_calls: execute tools, feed results back, repeat
    3. If finish_reason = stop: stream final text and done
    """
    api_messages = []
    if system_prompt and not is_reasoning_model(model):
        api_messages.append({"role": "system", "content": system_prompt})
    api_messages.extend(messages)

    for iteration in range(10):
        try:
            body = copilot_request_json(token, model, api_messages, use_tools=True)
        except urllib.error.HTTPError as e:
            err_body = e.read().decode() if hasattr(e, 'read') else str(e)
            send_event({"type": "error", "message": f"API error {e.code}: {err_body}"})
            return
        except Exception as e:
            send_event({"type": "error", "message": str(e)})
            return

        choice = body.get("choices", [{}])[0]
        finish_reason = choice.get("finish_reason", "stop")
        message = choice.get("message", {})

        api_messages.append(message)

        if finish_reason == "tool_calls":
            tool_calls = message.get("tool_calls", [])
            for tc in tool_calls:
                fn = tc.get("function", {})
                tool_name = fn.get("name", "")
                try:
                    tool_args = json.loads(fn.get("arguments", "{}"))
                except Exception:
                    tool_args = {}

                send_event({
                    "type": "tool_call",
                    "id": tc.get("id", ""),
                    "name": tool_name,
                    "args": tool_args
                })

                output, is_error = execute_tool(tool_name, tool_args)

                send_event({
                    "type": "tool_result",
                    "name": tool_name,
                    "output": output,
                    "error": is_error
                })

                api_messages.append({
                    "role": "tool",
                    "tool_call_id": tc.get("id", ""),
                    "content": output
                })
        else:
            content = message.get("content", "")
            if content:
                send_event({"type": "text", "content": content})
            send_event({"type": "done"})
            return

    send_event({"type": "error", "message": "Reached maximum tool call iterations (10)"})


class BridgeHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[bridge] {self.address_string()} {fmt % args}", flush=True)

    def send_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        if self.path == "/status":
            token = get_token()
            self.send_json(200, {
                "status": "ok",
                "hasToken": token is not None,
                "tokenHint": (token[:10] + "...") if token else None,
                "version": "1.0",
                "tools": [t["function"]["name"] for t in TOOLS]
            })

        elif self.path == "/models":
            token = get_token()
            if not token:
                self.send_json(401, {"error": "No token"})
                return
            try:
                req = urllib.request.Request(
                    "https://api.githubcopilot.com/models",
                    headers={
                        "Authorization": f"Bearer {token.strip()}",
                        "Editor-Version": "vscode/1.85.0",
                        "Copilot-Integration-Id": "vscode-chat"
                    }
                )
                resp = urllib.request.urlopen(req, timeout=15)
                body = resp.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(body)
            except Exception as e:
                self.send_json(500, {"error": str(e)})

        else:
            self.send_json(404, {"error": "Not found"})

    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(content_length)
        try:
            body = json.loads(raw.decode())
        except Exception:
            self.send_json(400, {"error": "Invalid JSON"})
            return

        if self.path == "/chat":
            token = body.get("token") or get_token()
            if not token:
                self.send_json(401, {"error": "No token configured. Set one in the app Settings or save token to ~/.copilot/config.json"})
                return

            model = body.get("model", "claude-sonnet-4.6")
            messages = body.get("messages", [])
            system_prompt = body.get("systemPrompt", "")

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_cors_headers()
            self.end_headers()

            def send_event(event):
                try:
                    line = "data: " + json.dumps(event) + "\n\n"
                    self.wfile.write(line.encode())
                    self.wfile.flush()
                except Exception:
                    pass

            run_agent_loop(token, model, messages, system_prompt, send_event)

        elif self.path == "/execute":
            name = body.get("tool", "bash")
            args = body.get("args", {})
            output, is_error = execute_tool(name, args)
            self.send_json(200, {"output": output, "error": is_error})

        else:
            self.send_json(404, {"error": "Not found"})


def main():
    token = get_token()
    print(f"[bridge] Copilot Chat Bridge v1.0", flush=True)
    if token:
        print(f"[bridge] Token: {token[:10]}...", flush=True)
    else:
        print("[bridge] WARNING: No token found.", flush=True)
        print("[bridge]   Run with: python3 bridge.py --token gho_xxxx", flush=True)
        print("[bridge]   Or set:   export COPILOT_TOKEN=gho_xxxx", flush=True)
        print("[bridge]   App can also send token from its Settings screen.", flush=True)

    server = HTTPServer(("127.0.0.1", PORT), BridgeHandler)
    print(f"[bridge] Listening on http://127.0.0.1:{PORT}", flush=True)
    print(f"[bridge] Tools: {', '.join(t['function']['name'] for t in TOOLS)}", flush=True)
    print("[bridge] Press Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[bridge] Stopped.", flush=True)


if __name__ == "__main__":
    main()
