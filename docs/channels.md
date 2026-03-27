# Multi-Channel — One Agent, Every Platform

When `atmosphere-channels` and `atmosphere-agent` are both on the classpath, `@Command` slash commands are automatically routed to all configured channels. AI responses on external channels go through the full `AiPipeline` (memory, tools, guardrails, RAG, metrics).

## Channel Matrix

| Channel | Activation | Commands | AI |
|---------|-----------|:--------:|:--:|
| Web (WebSocket) | Built-in | `@Command` via `CommandRouter` | `@Prompt` + `@AiTool` + `AiInterceptor` |
| Slack | `SLACK_BOT_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| Telegram | `TELEGRAM_BOT_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| Discord | `DISCORD_BOT_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| WhatsApp | `WHATSAPP_ACCESS_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |
| Messenger | `MESSENGER_PAGE_TOKEN` | Auto-routed | `AiPipeline` (full chain, no `AiInterceptor`) |

## How It Works

Commands (messages starting with `/`) route through all registered agents — first match wins. Natural-language messages go through the **first registered agent's** `AiPipeline` only. Multi-agent NL routing is not yet supported; use commands for multi-agent dispatch.

**Web (WebSocket)** uses the full `AiStreamingSession` pipeline, which includes `AiInterceptor` (pre/post processing that requires `AtmosphereResource`).

**External channels** (Slack, Telegram, etc.) use `AiPipeline` directly — a resource-free variant that runs the same chain (memory, tools, guardrails, RAG, metrics) but skips `AiInterceptor` since there is no persistent `AtmosphereResource` for webhook-based transports.

## Connecting a Channel

Set the platform's bot token as an environment variable and add `atmosphere-channels` to your classpath:

```bash
SLACK_BOT_TOKEN=xoxb-... LLM_API_KEY=sk-... ./mvnw spring-boot:run
```

See [spring-boot-dentist-agent](../samples/spring-boot-dentist-agent/) for a working multi-channel example and the [channels tutorial](https://atmosphere.github.io/docs/tutorial/23-channels/) for setup guides.

## Known Limitations

- **Multi-agent NL routing**: When multiple agents are registered, natural-language messages are handled by the first agent that has a pipeline. Only command routing supports multi-agent dispatch.
- **No `AiInterceptor`**: Channel messages arrive as HTTP webhooks (no suspended connection), so `AiInterceptor` (which requires `AtmosphereResource`) is skipped.
