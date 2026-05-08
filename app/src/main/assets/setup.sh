#!/data/data/com.termux/files/usr/bin/bash
# ClaudeCode Setup Script - runs inside Termux terminal

set -e

# Enable allow-external-apps FIRST so subsequent app→Termux intents work.
# This must happen before anything else so a partial/retried run still sets it.
mkdir -p ~/.termux
if ! grep -q "allow-external-apps" ~/.termux/termux.properties 2>/dev/null; then
    echo "allow-external-apps = true" >> ~/.termux/termux.properties
fi

echo ""
echo "╔══════════════════════════════════════╗"
echo "║   ClaudeCode Setup  (v1.2.0)         ║"
echo "║   Please keep this screen open...   ║"
echo "╚══════════════════════════════════════╝"
echo ""

# Step 1: Install required packages
echo "[1/5] Installing packages (pkg install)..."
pkg update -y 2>&1 | tail -3 || true
pkg install -y proot-distro socat lsof curl python 2>&1 | tail -5
echo "Packages ready."

# Step 2: Install Ubuntu via proot-distro
echo ""
echo "[2/5] Setting up Ubuntu Linux..."
if proot-distro list 2>/dev/null | grep -q "^ubuntu"; then
    echo "Ubuntu already installed, skipping download."
else
    echo "Downloading Ubuntu (~300 MB, please wait)..."
    proot-distro install ubuntu
fi
echo "Ubuntu ready."

# Step 3: Detect CPU architecture
ARCH=$(uname -m)
case "$ARCH" in
    aarch64)        NODE_ARCH="arm64"  ;;
    armv7l|armv7)  NODE_ARCH="armv7l" ;;
    x86_64)         NODE_ARCH="x64"    ;;
    i686|i386)      NODE_ARCH="x86"    ;;
    *)              NODE_ARCH="arm64"  ;;
esac
echo ""
echo "Device arch: $ARCH  →  Node arch: $NODE_ARCH"

# Step 4: Install Node.js and Claude Code inside Ubuntu
echo ""
echo "[3/5] Installing Node.js + Claude Code inside Ubuntu..."
proot-distro login ubuntu -- bash -c "
set -e
if [ ! -f /root/node-v20.11.0-linux-${NODE_ARCH}/bin/node ]; then
    echo 'Downloading Node.js v20.11.0 for ${NODE_ARCH}...'
    curl -fsSL 'https://nodejs.org/dist/v20.11.0/node-v20.11.0-linux-${NODE_ARCH}.tar.gz' -o /tmp/node.tar.gz
    cd /root && tar -xzf /tmp/node.tar.gz --no-same-owner && rm -f /tmp/node.tar.gz
    echo 'Node.js installed.'
fi
export PATH=\"/root/node-v20.11.0-linux-${NODE_ARCH}/bin:\$PATH\"
if ! claude --version 2>/dev/null; then
    echo 'Installing Claude Code (npm install -g)...'
    npm install -g @anthropic-ai/claude-code --no-audit --no-fund 2>&1 | tail -5
fi
echo 'Claude Code ready.'
"

# Step 5: Install proxy inside Ubuntu
echo ""
echo "[4/5] Installing Claude Code proxy..."
proot-distro login ubuntu -- bash -c "
if [ ! -d /root/free-claude-code-main ]; then
    apt-get update -qq 2>/dev/null || true
    apt-get install -y -qq python3 python3-pip 2>/dev/null || true
    echo 'Downloading proxy from GitHub...'
    curl -fsSL 'https://github.com/Alishahryar1/free-claude-code/archive/refs/heads/main.zip' \
        -o /root/proxy.zip
    cd /root && python3 -c \"import zipfile; zipfile.ZipFile('proxy.zip').extractall('.')\"
    rm -f /root/proxy.zip
    echo 'Installing proxy Python dependencies...'
    pip3 install uvicorn fastapi httpx --break-system-packages 2>/dev/null || true
fi
echo 'Proxy ready.'
"

# Step 6: Write launcher and bridge scripts
echo ""
echo "[5/5] Writing startup scripts..."

# ~/.claude_launcher.sh — called by socat per new connection; runs claude inside Ubuntu
cat > ~/.claude_launcher.sh << 'LAUNCHEREOF'
#!/data/data/com.termux/files/usr/bin/bash
[ -f ~/.claude_env ] && source ~/.claude_env
NODE_ARCH="${NODE_ARCH:-arm64}"
stty rows 50 cols 160 2>/dev/null || true
exec proot-distro login ubuntu -- env \
    HOME=/root \
    PATH="/root/node-v20.11.0-linux-${NODE_ARCH}/bin:/usr/local/bin:/usr/bin:/bin" \
    TERM=xterm-256color \
    LANG=en_US.UTF-8 \
    ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL}" \
    ANTHROPIC_AUTH_TOKEN="${ANTHROPIC_AUTH_TOKEN}" \
    ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY}" \
    ANTHROPIC_MODEL="${ANTHROPIC_MODEL}" \
    claude
LAUNCHEREOF
chmod +x ~/.claude_launcher.sh

# ~/.claudebridge.sh — sets env config, optionally starts proxy, starts socat on 8083
cat > ~/.claudebridge.sh << 'BRIDGEEOF'
#!/data/data/com.termux/files/usr/bin/bash
# Usage: ~/.claudebridge.sh <mode> <apiKey> <modelId> <baseUrl>
MODE="${1:-proxy}"
API_KEY="${2:-}"
MODEL="${3:-}"
BASE_URL="${4:-}"

ARCH=$(uname -m)
case "$ARCH" in
    aarch64)      NODE_ARCH="arm64"  ;;
    armv7l|armv7) NODE_ARCH="armv7l" ;;
    x86_64)       NODE_ARCH="x64"    ;;
    *)            NODE_ARCH="arm64"  ;;
esac

# Write runtime env config (sourced by ~/.claude_launcher.sh per session)
{
    echo "NODE_ARCH=\"${NODE_ARCH}\""
    echo "ANTHROPIC_MODEL=\"${MODEL}\""
    if [ "$MODE" = "proxy" ]; then
        echo "ANTHROPIC_BASE_URL=\"http://localhost:8082\""
        echo "ANTHROPIC_AUTH_TOKEN=\"freecc\""
        echo "ANTHROPIC_API_KEY=\"\""
    elif [ "$MODE" = "gemini" ]; then
        echo "ANTHROPIC_API_KEY=\"${API_KEY}\""
        echo "ANTHROPIC_BASE_URL=\"${BASE_URL:-https://generativelanguage.googleapis.com/v1beta/openai/}\""
        echo "ANTHROPIC_AUTH_TOKEN=\"\""
    else
        echo "ANTHROPIC_API_KEY=\"\""
        echo "ANTHROPIC_BASE_URL=\"\""
        echo "ANTHROPIC_AUTH_TOKEN=\"\""
    fi
} > ~/.claude_env

# Start proxy in background if proxy mode
if [ "$MODE" = "proxy" ]; then
    proot-distro login ubuntu -- bash -c \
        "kill \$(lsof -t -i:8082 2>/dev/null) 2>/dev/null; true" 2>/dev/null || true
    nohup bash -c "proot-distro login ubuntu -- bash -c \
        'export PATH=\"/root/node-v20.11.0-linux-${NODE_ARCH}/bin:\$PATH\"; \
         cd ~/free-claude-code-main && python3 -m uvicorn server:app --host 0.0.0.0 --port 8082 \
         2>&1'" > /tmp/proxy.log 2>&1 &
    sleep 2
fi

# Kill any existing socat on port 8083
lsof -t -i:8083 2>/dev/null | xargs kill -9 2>/dev/null || true

echo "Claude Code bridge listening on port 8083..."
exec socat TCP-LISTEN:8083,fork,reuseaddr \
    "EXEC:${HOME}/.claude_launcher.sh,pty,raw,echo=0"
BRIDGEEOF
chmod +x ~/.claudebridge.sh
echo "Scripts created."

echo ""
echo "╔══════════════════════════════════════╗"
echo "║         SETUP COMPLETE!              ║"
echo "║  Switch back to the ClaudeCode app  ║"
echo "╚══════════════════════════════════════╝"
echo ""
echo "Starting bridge on port 8083..."

# Start the bridge — app polls this port to detect setup completion
exec ~/.claudebridge.sh proxy "" "" ""
