# spring-boot-one-dep-agent

**One dependency + a single `@Agent` class = a running streaming chat app.**

This sample is the literal proof of the Atmosphere-4 "Try it" claim. The whole
app is:

- **one Atmosphere dependency** — `atmosphere-ai-spring-boot-starter` (see
  [`pom.xml`](pom.xml)); and
- **one `@Agent` class** — [`ChatAgent`](src/main/java/org/atmosphere/samples/springboot/onedepagent/ChatAgent.java),
  a single `@Prompt` method that calls `session.stream(message)`.

Everything else — the embedded web server, the Atmosphere runtime + Console, the
AI streaming pipeline, `@Agent` processing, and the keyless demo runtime — rides
in transitively through that one starter.

## Run it

```bash
./mvnw spring-boot:run -pl samples/spring-boot-one-dep-agent
```

Then open <http://localhost:8101/> — the root path redirects to the bundled
Atmosphere Console. Type a message; the reply streams back token-by-token.

With **no API key** the framework's built-in demo runtime streams a canned
response, so the app works out of the box. To stream from a real
OpenAI-compatible provider, export a key — no code or config change:

```bash
LLM_API_KEY=sk-... LLM_MODEL=gpt-4o-mini \
  ./mvnw spring-boot:run -pl samples/spring-boot-one-dep-agent
```

The AI auto-config reads `LLM_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY`
(and `LLM_MODEL` / `LLM_BASE_URL` / `LLM_MODE`) straight from the environment.
You can also set `atmosphere.ai.api-key` / `atmosphere.ai.model` /
`atmosphere.ai.base-url` in `application.yml`.

## The one class that matters

```java
@Agent(name = "chat", description = "One-dependency streaming chat agent")
public class ChatAgent {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

`@Agent` makes the framework register a streaming web handler at
`/atmosphere/agent/chat`. `session.stream(...)` routes the prompt through the AI
pipeline to the highest-priority `AgentRuntime` on the classpath (the demo
runtime when keyless), which pushes the reply as
`{"type":"streaming-text",...}` frames followed by a `{"type":"complete",...}`
frame — the exact wire shape the Console renders.

`OneDepAgentApplication` is the only other file: a bare `@SpringBootApplication`
`main`. It is boot boilerplate, not application logic.

## What "one dependency" means here

The `pom.xml` declares exactly one Atmosphere/Spring dependency — the AI
starter. The two additional entries are not application dependencies:

- `logback-core` / `logback-classic` at **runtime** scope — a logging backend
  only. The repo's parent POM inherits logback at `test` scope, which shadows
  the runtime copy the starter would otherwise transit; re-declaring it restores
  console logging.
- `spring-boot-starter-test` at **test** scope — for the delivery test below.

## Delivery test

[`OneDepAgentStreamingE2ETest`](src/test/java/org/atmosphere/samples/springboot/onedepagent/OneDepAgentStreamingE2ETest.java)
boots the sample on a random port and drives one streaming chat turn end to end
over a real WebSocket, asserting that **multiple** `streaming-text` frames arrive
(streamed tokens, not a single blob) and that the turn terminates with a
`complete` frame. Keyless — no provider, no network.

```bash
./mvnw test -pl samples/spring-boot-one-dep-agent
```

## Spring Boot 4 only

The one-dependency promise is realised by the Spring Boot 4 artifact
`atmosphere-ai-spring-boot-starter`, which pulls the web server + AI layer
non-optionally. There is no AI-inclusive Spring Boot 3 starter (the SB3 base
starter keeps the AI layer optional), so this sample ships SB4 only.
