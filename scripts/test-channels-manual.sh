#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────
# test-channels-manual.sh — Manual integration test for messaging channels
#
# Prerequisites:
#   brew install ngrok   (or download from https://ngrok.com)
#   ngrok config add-authtoken YOUR_TOKEN
#
# Usage:
#   ./scripts/test-channels-manual.sh telegram
#   ./scripts/test-channels-manual.sh slack
#   ./scripts/test-channels-manual.sh discord
#   ./scripts/test-channels-manual.sh whatsapp
#   ./scripts/test-channels-manual.sh messenger
#   ./scripts/test-channels-manual.sh all
# ──────────────────────────────────────────────────────────────────────────
set -euo pipefail

CHANNEL="${1:-help}"
PORT=8080
ROOT="$(git rev-parse --show-toplevel)"

red()   { printf "\033[0;31m%s\033[0m\n" "$*"; }
green() { printf "\033[0;32m%s\033[0m\n" "$*"; }
blue()  { printf "\033[0;34m%s\033[0m\n" "$*"; }
bold()  { printf "\033[1m%s\033[0m\n" "$*"; }

# ── Helpers ──────────────────────────────────────────────────────────────

start_ngrok() {
    blue "Starting ngrok tunnel on port $PORT..."
    ngrok http $PORT --log=stdout > /tmp/ngrok.log 2>&1 &
    NGROK_PID=$!
    sleep 3
    NGROK_URL=$(curl -s http://localhost:4040/api/tunnels | python3 -c "import sys,json; print(json.load(sys.stdin)['tunnels'][0]['public_url'])" 2>/dev/null || echo "")
    if [ -z "$NGROK_URL" ]; then
        red "Failed to start ngrok. Is it installed? Run: brew install ngrok"
        exit 1
    fi
    green "Tunnel: $NGROK_URL"
}

stop_ngrok() {
    kill $NGROK_PID 2>/dev/null || true
}

start_app() {
    blue "Building and starting the sample app..."
    cd "$ROOT"
    ./mvnw install -pl modules/channels -DskipTests -q 2>/dev/null
    ./mvnw spring-boot:run -pl samples/spring-boot-channels-chat \
        -Dspring-boot.run.arguments="--server.port=$PORT" &
    APP_PID=$!
    sleep 10
    if ! curl -s -o /dev/null -w "" http://localhost:$PORT/atmosphere/console/ 2>/dev/null; then
        red "App failed to start on port $PORT"
        exit 1
    fi
    green "App running on port $PORT"
}

stop_app() {
    kill $APP_PID 2>/dev/null || true
}

cleanup() {
    stop_app
    stop_ngrok
}
trap cleanup EXIT

check_env() {
    local var="$1"
    if [ -z "${!var:-}" ]; then
        red "Missing: $var"
        return 1
    fi
    green "  $var is set"
    return 0
}

# ── Telegram ─────────────────────────────────────────────────────────────

test_telegram() {
    bold "═══ TELEGRAM ═══"
    echo ""
    blue "Step 1: Check credentials"
    check_env TELEGRAM_BOT_TOKEN || { red "Get it from @BotFather on Telegram"; return 1; }
    check_env TELEGRAM_WEBHOOK_SECRET || { red "Set any random string as webhook secret"; return 1; }

    blue "Step 2: Register webhook with Telegram"
    WEBHOOK_URL="$NGROK_URL/webhook/telegram"
    RESULT=$(curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
        -F "url=$WEBHOOK_URL" \
        -F "secret_token=$TELEGRAM_WEBHOOK_SECRET")
    echo "  Telegram response: $RESULT"
    if echo "$RESULT" | python3 -c "import sys,json; assert json.load(sys.stdin)['ok']" 2>/dev/null; then
        green "  Webhook registered: $WEBHOOK_URL"
    else
        red "  Webhook registration failed!"
        return 1
    fi

    blue "Step 3: Manual test"
    echo ""
    bold "  Action required:"
    echo "  1. Open Telegram and find your bot"
    echo "  2. Send a message: 'Hello from Atmosphere test'"
    echo "  3. Check the app logs for the incoming message"
    echo "  4. The bot should respond (demo response or LLM if API key is set)"
    echo ""
    read -p "  Press Enter when done testing (or 's' to skip)... " REPLY
    [ "$REPLY" = "s" ] && return 0

    blue "Step 4: Verify webhook info"
    curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getWebhookInfo" | python3 -m json.tool

    blue "Step 5: Cleanup — remove webhook"
    curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/deleteWebhook" | python3 -m json.tool
    green "Telegram test complete"
}

# ── Slack ─────────────────────────────────────────────────────────────────

test_slack() {
    bold "═══ SLACK ═══"
    echo ""
    blue "Step 1: Check credentials"
    check_env SLACK_BOT_TOKEN || { red "Create a Slack app at https://api.slack.com/apps"; return 1; }
    check_env SLACK_SIGNING_SECRET || { red "Find it under Basic Information > App Credentials"; return 1; }

    blue "Step 2: Configure Slack app"
    echo ""
    bold "  Manual setup required:"
    echo "  1. Go to https://api.slack.com/apps > Your App > Event Subscriptions"
    echo "  2. Enable Events"
    echo "  3. Set Request URL to: $NGROK_URL/webhook/slack"
    echo "     (Slack will send a URL verification challenge — the app handles it automatically)"
    echo "  4. Under 'Subscribe to bot events', add: message.im, message.channels"
    echo "  5. Go to OAuth & Permissions, add scopes: chat:write, channels:history, im:history"
    echo "  6. Install/reinstall the app to your workspace"
    echo ""
    read -p "  Press Enter when Slack app is configured... " REPLY

    blue "Step 3: Manual test"
    echo ""
    bold "  Action required:"
    echo "  1. Invite the bot to a channel: /invite @YourBot"
    echo "  2. Send a message mentioning the bot or DM it"
    echo "  3. Check app logs for the incoming message"
    echo "  4. The bot should respond in the channel/DM"
    echo ""
    read -p "  Press Enter when done testing... " REPLY

    green "Slack test complete"
}

# ── Discord ──────────────────────────────────────────────────────────────

test_discord() {
    bold "═══ DISCORD ═══"
    echo ""
    blue "Step 1: Check credentials"
    check_env DISCORD_BOT_TOKEN || { red "Create a bot at https://discord.com/developers/applications"; return 1; }

    blue "Step 2: Configure Discord bot"
    echo ""
    bold "  Manual setup required:"
    echo "  1. Go to https://discord.com/developers/applications > Your App"
    echo "  2. Go to Bot section"
    echo "  3. Enable these Privileged Gateway Intents:"
    echo "     - MESSAGE CONTENT Intent"
    echo "     - SERVER MEMBERS Intent (optional)"
    echo "  4. Go to OAuth2 > URL Generator"
    echo "     - Scopes: bot"
    echo "     - Bot Permissions: Send Messages, Read Message History"
    echo "  5. Copy the generated URL and open it to invite the bot to your test server"
    echo ""
    echo "  Note: Discord uses a WebSocket Gateway, not webhooks."
    echo "  The bot connects automatically when the app starts."
    echo "  No ngrok URL needed for Discord."
    echo ""
    read -p "  Press Enter when bot is invited to your server... " REPLY

    blue "Step 3: Manual test"
    echo ""
    bold "  Action required:"
    echo "  1. Go to your Discord test server"
    echo "  2. Send a message in any channel the bot can see"
    echo "  3. Check app logs — you should see 'Discord Gateway connected'"
    echo "  4. The bot should respond in the same channel"
    echo ""
    read -p "  Press Enter when done testing... " REPLY

    green "Discord test complete"
}

# ── WhatsApp ─────────────────────────────────────────────────────────────

test_whatsapp() {
    bold "═══ WHATSAPP ═══"
    echo ""
    blue "Step 1: Check credentials"
    check_env WHATSAPP_PHONE_NUMBER_ID || { red "Get from Meta Business > WhatsApp > API Setup"; return 1; }
    check_env WHATSAPP_ACCESS_TOKEN || { red "Generate a temporary token from API Setup page"; return 1; }
    check_env WHATSAPP_APP_SECRET || { red "Find it under App Settings > Basic > App Secret"; return 1; }

    blue "Step 2: Configure Meta webhook"
    echo ""
    bold "  Manual setup required:"
    echo "  1. Go to https://developers.facebook.com > Your App > WhatsApp > Configuration"
    echo "  2. Under Webhooks, click Edit"
    echo "  3. Callback URL: $NGROK_URL/webhook/whatsapp"
    echo "  4. Verify token: atmosphere (or any string — note: our controller auto-handles verification)"
    echo "  5. Subscribe to: messages"
    echo ""
    echo "  Note: You need a Meta Business account and a registered WhatsApp test number."
    echo "  For testing, Meta provides a test phone number in the API Setup page."
    echo "  You can only message numbers that have been added as test recipients."
    echo ""
    read -p "  Press Enter when webhook is configured... " REPLY

    blue "Step 3: Manual test"
    echo ""
    bold "  Action required:"
    echo "  1. Open WhatsApp on your phone"
    echo "  2. Send a message to your test business number"
    echo "  3. Check app logs for the incoming message"
    echo "  4. You should receive a response back on WhatsApp"
    echo ""
    echo "  Common issues:"
    echo "  - 'Message failed to send' → token expired (regenerate at API Setup)"
    echo "  - No incoming messages → check webhook subscription includes 'messages'"
    echo "  - 403 error → recipient not in test recipients list"
    echo ""
    read -p "  Press Enter when done testing... " REPLY

    green "WhatsApp test complete"
}

# ── Messenger ────────────────────────────────────────────────────────────

test_messenger() {
    bold "═══ MESSENGER ═══"
    echo ""
    blue "Step 1: Check credentials"
    check_env MESSENGER_PAGE_TOKEN || { red "Generate from Facebook Page Settings > Advanced Messaging"; return 1; }
    check_env MESSENGER_APP_SECRET || { red "Find under App Settings > Basic > App Secret"; return 1; }

    blue "Step 2: Configure Meta webhook"
    echo ""
    bold "  Manual setup required:"
    echo "  1. Go to https://developers.facebook.com > Your App > Messenger > Settings"
    echo "  2. Under Webhooks, click 'Add Callback URL'"
    echo "  3. Callback URL: $NGROK_URL/webhook/messenger"
    echo "  4. Verify token: atmosphere"
    echo "  5. Subscribe to: messages, messaging_postbacks"
    echo "  6. Select the Facebook Page to receive messages for"
    echo ""
    echo "  Note: Messenger requires a Facebook Page linked to your Meta app."
    echo "  For testing, you can message the page from your personal Facebook account."
    echo ""
    read -p "  Press Enter when webhook is configured... " REPLY

    blue "Step 3: Manual test"
    echo ""
    bold "  Action required:"
    echo "  1. Go to your Facebook Page on messenger.com or the Messenger app"
    echo "  2. Send a message to the page"
    echo "  3. Check app logs for the incoming message"
    echo "  4. You should receive a response back in Messenger"
    echo ""
    read -p "  Press Enter when done testing... " REPLY

    green "Messenger test complete"
}

# ── Main ─────────────────────────────────────────────────────────────────

case "$CHANNEL" in
    telegram|slack|whatsapp|messenger|discord|all)
        echo ""
        bold "╔══════════════════════════════════════════════════════════╗"
        bold "║  Atmosphere Channels — Manual Integration Test          ║"
        bold "╚══════════════════════════════════════════════════════════╝"
        echo ""

        if [ "$CHANNEL" != "discord" ]; then
            start_ngrok
        fi
        start_app

        echo ""
        green "Ready for testing!"
        echo ""

        if [ "$CHANNEL" = "all" ]; then
            test_telegram || true
            echo ""
            test_slack || true
            echo ""
            test_discord || true
            echo ""
            test_whatsapp || true
            echo ""
            test_messenger || true
        else
            test_$CHANNEL
        fi

        echo ""
        green "All tests complete. Cleaning up..."
        ;;
    *)
        echo "Usage: $0 {telegram|slack|discord|whatsapp|messenger|all}"
        echo ""
        echo "Prerequisites:"
        echo "  brew install ngrok"
        echo "  ngrok config add-authtoken YOUR_TOKEN"
        echo ""
        echo "Environment variables per platform:"
        echo "  Telegram:  TELEGRAM_BOT_TOKEN, TELEGRAM_WEBHOOK_SECRET"
        echo "  Slack:     SLACK_BOT_TOKEN, SLACK_SIGNING_SECRET"
        echo "  Discord:   DISCORD_BOT_TOKEN"
        echo "  WhatsApp:  WHATSAPP_PHONE_NUMBER_ID, WHATSAPP_ACCESS_TOKEN, WHATSAPP_APP_SECRET"
        echo "  Messenger: MESSENGER_PAGE_TOKEN, MESSENGER_APP_SECRET"
        echo "  LLM:       LLM_API_KEY (optional — demo mode without)"
        exit 1
        ;;
esac
