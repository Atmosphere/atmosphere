# Omnichannel AI Chat

Same AI assistant, every channel — powered by Atmosphere.

## Quick start (web only)

```bash
./mvnw spring-boot:run -pl samples/spring-boot-channels-chat
```

Open http://localhost:8080 — AI chat with streaming responses via WebSocket.

## Adding Telegram

1. Create a bot via [@BotFather](https://t.me/BotFather) and copy the token
2. Start a tunnel: `ngrok http 8080`
3. Register the webhook:
   ```bash
   curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
     -H "Content-Type: application/json" \
     -d '{"url": "https://<NGROK_URL>/webhook/telegram", "secret_token": "my-secret"}'
   ```
4. Run:
   ```bash
   TELEGRAM_BOT_TOKEN=<TOKEN> TELEGRAM_WEBHOOK_SECRET=my-secret \
     ./mvnw spring-boot:run -pl samples/spring-boot-channels-chat
   ```
5. Message your bot on Telegram

## Adding Slack

1. Create a Slack app at https://api.slack.com/apps
2. Enable Events API, subscribe to `message.channels`
3. Set the webhook URL to `https://<NGROK_URL>/webhook/slack`
4. Run:
   ```bash
   SLACK_BOT_TOKEN=xoxb-... SLACK_SIGNING_SECRET=... \
     ./mvnw spring-boot:run -pl samples/spring-boot-channels-chat
   ```

## Adding more channels

Set the env vars for any channel and it auto-activates:

| Channel | Required env vars |
|---------|------------------|
| Telegram | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET` |
| Slack | `SLACK_BOT_TOKEN`, `SLACK_SIGNING_SECRET` |
| Discord | `DISCORD_BOT_TOKEN`, `DISCORD_PUBLIC_KEY` |
| WhatsApp | `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET` |
| Messenger | `MESSENGER_PAGE_TOKEN`, `MESSENGER_APP_SECRET` |

## With a real LLM

```bash
LLM_API_KEY=your-gemini-key LLM_MODEL=gemini-2.5-flash \
  TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... \
  ./mvnw spring-boot:run -pl samples/spring-boot-channels-chat
```

## Architecture

```
Telegram/Slack/Discord/WhatsApp/Messenger
         |  webhooks
         v
   ChannelWebhookController
         |  IncomingMessage
         v
     ChannelBridge
         |  prompt
         v
   @AiEndpoint (OmnichannelChat)
         |  streaming response
         v
     ChannelBridge
         |  OutgoingMessage
         v
   Platform API (sendMessage / chat.postMessage / ...)

Web Browser
    |  atmosphere.js
    v
  @AiEndpoint (same endpoint, WebSocket transport)
```
