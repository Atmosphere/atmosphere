# Omnichannel AI Chat — one `@Agent`, every channel

Same AI assistant, every channel — built with a single `@Agent`. The persona lives
in a skill file; the framework wires that one agent to the web console **and** to
every configured messaging platform (Telegram, Slack, Discord, WhatsApp, Messenger).
No per-channel delivery code.

## How it works

`OmnichannelChat` is annotated `@Agent(name = "omnichannel", skillFile = "skill:omnichannel-chat")`.
The skill file `META-INF/skills/omnichannel-chat/SKILL.md` carries the system prompt
and a `## Channels` section. When `atmosphere-channels` is on the classpath, the
framework registers the agent's pipeline with `ChannelAiBridge`, which routes inbound
messages from every listed channel to the agent and sends the reply back through the
originating platform.

```
## Channels        <-- in SKILL.md: telegram, slack, discord, whatsapp, messenger
```

## Quick start (web only)

```bash
./mvnw spring-boot:run -pl samples/spring-boot-channels-chat
```

Open http://localhost:8080/atmosphere/console/ — AI chat with streaming responses
via WebSocket. (Demo mode answers with no API key; set `LLM_API_KEY` for a real LLM.)

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
5. Message your bot on Telegram — the same agent that answers in the browser answers here

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

## Key code

| File | Purpose |
|------|---------|
| `OmnichannelChat.java` | The `@Agent` — `@Prompt` streams every message through the pipeline |
| `META-INF/skills/omnichannel-chat/SKILL.md` | System prompt + `## Channels` the agent serves |
| `ConsoleEndpointAlias.java` | Aliases the agent handler to `/atmosphere/ai-chat` for the console |
| `application.yaml` | LLM settings (`atmosphere.ai.*`) and channel credentials (`atmosphere.channels.*`) |

## Architecture

```
Web browser ──WebSocket──┐
Telegram ─────webhook────┤
Slack ────────webhook────┤   @Agent(name = "omnichannel")
Discord ──────gateway────┤   skill:omnichannel-chat
WhatsApp ─────webhook────┤            │
Messenger ────webhook────┘     one AI pipeline
                                      │
                          ChannelAiBridge (channels) +
                          WebSocket handler (web)
                                      │
                              streaming response
                                      v
                       back through the originating surface
```

The same agent and skill serve every surface. Inbound channel messages are routed to
the agent's pipeline by `ChannelAiBridge` (from `atmosphere-channels`), which handles
platform format differences (max length, reply threading, markdown) transparently.
The `## Channels` list in the skill file is the agent's channel allow-list — only the
listed platforms reach this agent.
