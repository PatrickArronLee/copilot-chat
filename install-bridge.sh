#!/bin/bash
# Run this in Termux to install the Copilot Chat Bridge
set -e
curl -sL https://raw.githubusercontent.com/PatrickArronLee/copilot-chat/master/bridge.py -o ~/bridge.py
echo "Downloaded bridge.py to ~/bridge.py"
echo ""
echo "Start with:"
echo "  python3 ~/bridge.py --token YOUR_GITHUB_TOKEN"
echo ""
echo "Or set env var:"
echo "  export COPILOT_TOKEN=YOUR_GITHUB_TOKEN"
echo "  python3 ~/bridge.py"
