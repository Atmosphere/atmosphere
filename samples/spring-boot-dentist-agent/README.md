# Atmosphere @Agent — Dr. Molar (Dental Emergency Assistant)

A multi-channel dental emergency assistant built with `@Agent`. One agent class — the same `@Command` slash commands and AI tools work on **Web, Slack, Telegram, Discord, WhatsApp, and Messenger** simultaneously. A patient types `/firstaid` in Slack and gets the same instant response as in the browser.

## What It Does

Dr. Molar helps patients with broken, chipped, or cracked teeth. It can assess emergency severity, recommend pain management, and provide first-aid instructions. The agent works on every configured channel at once — no per-channel code needed.

### Slash Commands (work on every channel)

| Command | Description |
|---------|-------------|
| `/firstaid` | Step-by-step first-aid for a broken tooth |
| `/urgency` | Help determine how urgently you need a dentist |
| `/pain` | Pain management tips (OTC options, home remedies) |
| `/help` | List all commands (auto-generated) |

These commands are defined once with `@Command` in `DentistAgent.java`. When `atmosphere-channels` is on the classpath, they automatically appear on every channel — Slack, Telegram, Discord, WhatsApp, Messenger. Natural language messages on those same channels fall through to the LLM via `@Prompt`.

### AI Tools

| Tool | Parameters | Description |
|------|-----------|-------------|
| `assess_emergency` | `injury_type`, `pain_level`, `bleeding` | Classify severity: EMERGENCY, URGENT, SAME-DAY, SOON, ROUTINE |
| `pain_relief` | `pain_level`, `allergies` | Recommend pain management accounting for allergies |

## Running

```bash
# Demo mode (no API key needed)
atmosphere run spring-boot-dentist-agent

# Or from the repository root
./mvnw spring-boot:run -pl samples/spring-boot-dentist-agent

# With a real LLM
LLM_API_KEY=your-gemini-key ./mvnw spring-boot:run -pl samples/spring-boot-dentist-agent
```

Open http://localhost:8080/atmosphere/console/ in your browser.

### With Channels

```bash
# Slack
SLACK_BOT_TOKEN=xoxb-... SLACK_SIGNING_SECRET=... \
  ./mvnw spring-boot:run -pl samples/spring-boot-dentist-agent

# Telegram
TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... \
  ./mvnw spring-boot:run -pl samples/spring-boot-dentist-agent
```

## Try These

- `/firstaid` — instant 7-step first-aid guide (no LLM call)
- `/urgency` — triage checklist: ER, same-day, 1-2 days, or routine
- `/pain` — OTC meds, home remedies, and what to avoid
- `I chipped my front tooth and it hurts (6/10), no bleeding` — LLM invokes `assess_emergency`
- `I'm allergic to ibuprofen, pain level 7` — LLM invokes `pain_relief` with allergy awareness

## Key Code

| File | Purpose |
|------|---------|
| `DentistAgent.java` | `@Agent` with `@Command` and `@AiTool` methods |
| `prompts/dentist-skill.md` | System prompt with personality, skills, tools, channels, guardrails |
| `ChannelBridge.java` | Bridges Slack/Telegram messages to the agent (command routing + LLM) |
| `CollectingSession.java` | Collects streaming tokens into a complete response for channel sends |
| `ConsoleEndpointAlias.java` | Aliases the agent handler to `/atmosphere/ai-chat` for the console |
| `DemoResponseProducer.java` | Fallback responses when no LLM API key is set |

## Architecture

```
Web browser ──WebSocket──┐
Slack ────────webhook────┤
Telegram ─────webhook────┤   @Agent(name = "dentist")
Discord ──────gateway────┤            │
WhatsApp ─────webhook────┤       CommandRouter
Messenger ────webhook────┘        ┌───┴───┐
                            "/" prefix?   natural language
                                  │              │
                             @Command       @Prompt ──> LLM + @AiTool
                             (instant)                  (assess_emergency,
                                                         pain_relief)
```

The same `@Command` methods and `@Prompt` handler serve every channel. `/firstaid` in Slack returns the exact same first-aid steps as in the browser. The `ChannelBridge` handles message format differences (max length, reply threading, markdown support) transparently.

## Skill File

The `prompts/dentist-skill.md` defines Dr. Molar's persona and capabilities:

```markdown
# Dr. Molar — Emergency Dental Assistant
You are Dr. Molar, a friendly and knowledgeable dental emergency assistant.

## Skills          <-- extracted for A2A Agent Card
## Tools           <-- cross-referenced with @AiTool methods
## Channels        <-- validated against classpath (slack, telegram, web)
## Guardrails      <-- LLM self-enforces (always state you're an AI, never diagnose)
```

The entire file becomes the system prompt. Sections are also parsed for protocol metadata (A2A, MCP).

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` | _(none)_ | API key for Gemini, OpenAI, or compatible. Omit for demo mode. |
| `LLM_MODEL` | `gemini-2.5-flash` | Model name |
| `SLACK_BOT_TOKEN` | _(none)_ | Slack bot token (`xoxb-...`) |
| `SLACK_SIGNING_SECRET` | _(none)_ | Slack app signing secret |
| `TELEGRAM_BOT_TOKEN` | _(none)_ | Telegram bot token from @BotFather |
| `TELEGRAM_WEBHOOK_SECRET` | _(none)_ | Random string for webhook verification |
