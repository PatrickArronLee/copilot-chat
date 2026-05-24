#!/bin/bash
# Usage: bash run-bridge.sh YOUR_GITHUB_TOKEN
TOKEN="$1"
if [ -z "$TOKEN" ]; then
  echo "Usage: bash run-bridge.sh YOUR_GITHUB_TOKEN"
  exit 1
fi
echo "Downloading bridge.py..."
curl -sL https://raw.githubusercontent.com/PatrickArronLee/copilot-chat/master/bridge.py -o ~/bridge.py
echo "Starting bridge..."
python3 ~/bridge.py --token "$TOKEN"
