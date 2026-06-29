# spring-boot-spring-ai-advisors

Bind your **own**, fully configured Spring AI `ChatClient` to Atmosphere with
`SpringAiAgentRuntime.setChatClient(...)` — Atmosphere keeps your
`defaultAdvisors(...)`, and you can attach **more advisors per request**.

This sample proves the three claims of the Atmosphere 4 blog §3 with a real,
observable side effect (not a "the bean exists" hand-wave): each advisor records
its name when it actually runs inside the `ChatClient` advisor chain.

## The three claims

1. **Bind your own client.** `BoundChatClientConfig` builds a `ChatClient`
   (`ChatClient.builder(model).defaultAdvisors(...).build()`) and hands it to
   `SpringAiAgentRuntime.setChatClient(client)`. From then on every Atmosphere AI
   request runs through *your* client.
2. **Atmosphere keeps your `defaultAdvisors(...)`.** The runtime dispatches via
   `client.prompt()`, so the default advisor you configured fires on every
   request — Atmosphere does nothing special to make that happen.
3. **Attach more advisors per request.** `PerRequestAuditInterceptor` stamps a
   second advisor onto a single request via `SpringAiAdvisors`. Atmosphere copies
   that metadata onto the `AgentExecutionContext`; `SpringAiAgentRuntime` reads it
   back with `SpringAiAdvisors.from(context)` and appends it through
   `promptSpec.advisors(...)`. That request runs **both** advisors; every other
   request runs only the default.

## The advisor

`AuditingAdvisor` is a minimal **real** Spring AI advisor — it implements both
`CallAdvisor` and `StreamAdvisor`, records its name into `AdvisorAuditLog` when it
runs, and delegates to the rest of the chain. It is deliberately *not* a heavy
`QuestionAnswerAdvisor` (which would need an embedding model and a vector store)
so the demonstration is deterministic and fully offline. The recorded invocation
is the observable proof that the advisor executed.

## Offline by design

The terminal model is `LocalEchoChatModel`, a deterministic `ChatModel` that
echoes the prompt back. It sits at the **end** of the advisor chain purely so the
sample runs with **no API key and no network**. The integration this sample
proves — the Spring AI `ChatClient` + advisor chain + `setChatClient(...)`
binding — is entirely real; only the model that terminates the chain is local.
To talk to a real provider, swap `LocalEchoChatModel` for any real `ChatModel`
(e.g. `OpenAiChatModel`) in `BoundChatClientConfig`; the `defaultAdvisors(...)`
and per-request advisors fire exactly the same way.

## Run it

```bash
./mvnw spring-boot:run -pl samples/spring-boot-spring-ai-advisors
```

Then drive the bundled Atmosphere Console at <http://localhost:8098> (it connects
to the `@AiEndpoint` at `/atmosphere/ai-chat`):

- Send a normal message (e.g. `hello`) — the **default** advisor runs.
- Send a message containing the word `audit` (e.g. `audit my last answer`) — the
  default advisor **and** the per-request advisor both run for that turn.

Confirm which advisors fired:

```bash
curl http://localhost:8098/api/advisors/audit-log
# {"invocations":[...],"default-advisor":2,"per-request-advisor":1}

curl -X POST http://localhost:8098/api/advisors/audit-log/clear   # reset counters
```

## Proof test

`SpringAiAdvisorRoutingTest` drives the real `SpringAiAgentRuntime` (the same
dispatch the `@AiEndpoint` uses) over the real bound `ChatClient`:

- `defaultAdvisorRunsThroughBoundClientOnEveryRequest` — a normal request runs
  the default advisor once and no per-request advisor; the model's echo proves
  the request traversed the chain.
- `perRequestAdvisorAttachedByInterceptorAlsoRuns` — drives the live
  interceptor → metadata → context → runtime path; both the default and the
  per-request advisor run.
- `nonTriggerRequestAttachesNoPerRequestAdvisor` — a non-`audit` request attaches
  nothing, so only the default advisor runs.

```bash
./mvnw test -pl samples/spring-boot-spring-ai-advisors -Dtest=SpringAiAdvisorRoutingTest
```

## Key files

| File | Role |
|------|------|
| `BoundChatClientConfig` | Builds the `ChatClient` with a default advisor and binds it via `setChatClient(...)` |
| `AuditingAdvisor` | Real Spring AI `Call`/`StreamAdvisor` that records it ran |
| `AdvisorAuditLog` | The observable side effect — advisor invocations in order |
| `PerRequestAuditInterceptor` | Attaches a per-request advisor via `SpringAiAdvisors` when the message contains `audit` |
| `LocalEchoChatModel` | Deterministic offline `ChatModel` terminating the chain |
| `AdvisorChatEndpoint` | The runnable `@AiEndpoint` chat surface |
| `AdvisorAuditController` | `GET /api/advisors/audit-log` to see which advisors fired |
