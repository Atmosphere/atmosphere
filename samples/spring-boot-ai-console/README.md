# Zero-Code AI Chat Console

The simplest possible Atmosphere AI chat — **no Java handler code, no frontend code**. Just add two dependencies, set an API key, and open the browser.

## How It Works

The `atmosphere-spring-boot-starter` + `atmosphere-ai` combo auto-configures:

1. An AI chat endpoint at `/atmosphere/ai-chat` (WebSocket)
2. A built-in Vue chat console at `/atmosphere/console/`
3. LLM settings from `application.yml` (with environment variable fallback)

The only Java file is the standard `@SpringBootApplication` class.

## Running

```bash
export GEMINI_API_KEY=your-key-here
../../mvnw spring-boot:run -pl samples/spring-boot-ai-console
```

Open http://localhost:8080/atmosphere/console/

## Using Other Providers

### OpenAI

```yaml
atmosphere:
  ai:
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
```

### Ollama (local)

```yaml
atmosphere:
  ai:
    mode: local
    model: llama3.2
```

## Customizing

Override any setting in `application.yml`:

```yaml
atmosphere:
  ai:
    path: /my-chat                    # Custom endpoint path
    system-prompt: You are a pirate.  # Custom personality
    conversation-memory: true         # Multi-turn memory (default)
    max-history-messages: 50          # Larger context window
    timeout: 300000                   # 5-minute timeout
```

Or define your own `@AiEndpoint` class for full control — the default endpoint is automatically skipped.
