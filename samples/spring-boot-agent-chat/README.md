# Atmosphere @Agent — DevOps Assistant

A DevOps assistant built with `@Agent` — Atmosphere's unified agent annotation. One class gives you an AI endpoint, slash commands, AI tools, and a skill file that doubles as the system prompt. Add `atmosphere-channels` to the classpath and the same commands and AI pipeline work on Slack, Telegram, Discord, WhatsApp, and Messenger — zero code changes.

## What It Does

The DevOps agent monitors services, manages deployments, and responds to incidents. It demonstrates the full `@Agent` feature set:

- **Slash commands** — instant, deterministic actions that bypass the LLM. Work on **every channel**: Web, Slack, Telegram, Discord, WhatsApp, Messenger
- **AI tools** — structured functions the LLM can invoke during inference
- **Cross-channel delivery** — one `@Agent` class, every channel gets commands + AI automatically via `atmosphere-channels`
- **Skill file** — a markdown file that serves as both system prompt and agent metadata
- **Demo mode** — works out-of-the-box without an API key

### Slash Commands

| Command | Description | Confirmation? |
|---------|-------------|:------------:|
| `/status` | Show health of all services | No |
| `/deploy <service> [version]` | Deploy to staging | Yes |
| `/uptime` | Show agent uptime | No |
| `/incidents` | List active incidents | No |
| `/help` | List all commands (auto-generated) | No |

### AI Tools

| Tool | Description |
|------|-------------|
| `check_service` | Check the health status of a specific service |
| `get_metrics` | Get performance metrics (CPU, memory, latency, errors) |

## Running

```bash
# Demo mode (no API key needed)
atmosphere run spring-boot-agent-chat

# Or from the repository root
./mvnw spring-boot:run -pl samples/spring-boot-agent-chat

# With a real LLM
LLM_API_KEY=your-gemini-key ./mvnw spring-boot:run -pl samples/spring-boot-agent-chat
```

Open http://localhost:8080/atmosphere/console/ in your browser.

## Try These

- `/status` — instant service health (no LLM call)
- `/deploy api-gateway 2.1.0` — triggers confirmation prompt, then deploys
- `/help` — auto-generated list of all commands
- `What's the CPU usage on the payment service?` — LLM invokes `get_metrics` tool
- `Is the notification service healthy?` — LLM invokes `check_service` tool

## Key Code

| File | Purpose |
|------|---------|
| `DevOpsAgent.java` | `@Agent` with `@Command` and `@AiTool` methods |
| `prompts/devops-skill.md` | System prompt + agent metadata (skills, tools, channels, guardrails) |
| `DemoResponseProducer.java` | Fallback streaming responses when no LLM API key is set |
| `LlmConfig.java` | Bridges Spring `@Value` properties to `AiConfig` |

## Architecture

```
Web browser ──WebSocket──┐
Slack ────────webhook────┤
Telegram ─────webhook────┤     @Agent(name = "devops")
Discord ──────gateway────┤              │
WhatsApp ─────webhook────┘         CommandRouter
                                    ┌───┴───┐
                              "/" prefix?   natural language
                                    │            │
                               @Command      @Prompt
                               (instant)         │
                                            LLM + @AiTool
```

Every channel gets the same commands and AI pipeline. Add `atmosphere-channels` to the classpath and set the channel's bot token — that's it.

## How @Agent Works

```java
@Agent(name = "devops",
       skillFile = "prompts/devops-skill.md",
       description = "DevOps assistant")
public class DevOpsAgent {

    @Command(value = "/status", description = "Show service health")
    public String status() { ... }

    @Command(value = "/deploy", description = "Deploy to staging",
             confirm = "Deploy to staging environment?")
    public String deploy(String args) { ... }

    @AiTool(name = "check_service", description = "Check service health")
    public String checkService(@Param("service") String service) { ... }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

The `@Agent` annotation:
1. Registers an AI endpoint at `/atmosphere/agent/devops`
2. Parses `devops-skill.md` as the system prompt
3. Scans `@Command` methods and builds a `CommandRouter`
4. Wires `@AiTool` methods into the LLM pipeline
5. If `atmosphere-channels` on classpath → routes commands and AI to Slack, Telegram, Discord, WhatsApp, Messenger
6. If `atmosphere-a2a` on classpath → auto-generates A2A Agent Card with skills and channels
7. If `atmosphere-mcp` on classpath → exposes `@AiTool` + `@Command` as MCP tools

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_API_KEY` | _(none)_ | API key for Gemini, OpenAI, or any compatible provider. Omit for demo mode. |
| `LLM_MODEL` | `gemini-2.5-flash` | Model name |
| `LLM_BASE_URL` | _(auto)_ | Custom API base URL |
