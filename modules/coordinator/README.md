# Atmosphere Coordinator

Multi-agent orchestration module for Atmosphere. Provides `@Coordinator`, `@Fleet`, `@AgentRef`, `AgentFleet` injection for parallel fan-out, sequential pipelines, a pluggable coordination journal, and quality evaluation — all wired at startup with no boilerplate.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-coordinator</artifactId>
    <version>4.0.34</version>
</dependency>
```

## Minimal Example

```java
@Coordinator(name = "ceo", skillFile = "prompts/ceo-skill.md",
             description = "Executive coordinator", version = "1.0.0")
@Fleet({
    @AgentRef(type = ResearchAgent.class),
    @AgentRef(value = "finance", version = "2.0.0")
})
public class CeoCoordinator {

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        var research = fleet.agent("research").call("web_search", Map.of("query", message));
        session.stream("Synthesis: " + research.textOr("(no result)"));
    }
}
```

`@Coordinator` subsumes `@Agent` — the `CoordinatorProcessor` handles base agent setup (skill file, AI runtime, protocol bridges) and adds fleet wiring on top. The `@Prompt` method runs on a virtual thread, so blocking agent calls do not block Atmosphere's thread pool. `AgentFleet` is injected automatically at dispatch time.

`@Coordinator` requires `@Fleet` — startup fails with a clear error if the annotation is missing.

## Parallel Fan-out and Sequential Pipeline

`AgentFleet` provides two execution patterns. `parallel()` dispatches all calls concurrently on virtual threads and waits for all results. `pipeline()` executes calls in declaration order and aborts immediately on the first failure.

```java
@Prompt
public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
    // Fan out to research and finance simultaneously
    var results = fleet.parallel(
        fleet.call("research", "web_search", Map.of("query", message)),
        fleet.call("finance",  "market_data", Map.of("ticker", "ATMS"))
    );

    var researchText = results.get("research").textOr("no data");
    var financeText  = results.get("finance").textOr("no data");

    // Then run a sequential pipeline: draft -> review -> format
    var draft = fleet.call("drafter", "draft",  Map.of("context", researchText));
    var review = fleet.call("reviewer", "review", Map.of("draft", ""));
    var final_ = fleet.call("formatter", "format", Map.of("reviewed", ""));

    var report = fleet.pipeline(draft, review, final_);
    session.stream(report.textOr("Pipeline failed"));
}
```

`AgentCall` is a pure spec record (no side effects). It is created by `fleet.call()` and executed by `parallel()` or `pipeline()`. The same call spec can be reused across invocations.

## Coordination Journal

The journal records the full execution graph of every coordination. It is discovered via `ServiceLoader` — drop `InMemoryCoordinationJournal` (or a custom implementation) into `META-INF/services/org.atmosphere.coordinator.journal.CoordinationJournal` and journaling activates automatically. `fleet.journal()` returns `CoordinationJournal.NOOP` when no journal is registered.

```java
@Prompt
public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
    var results = fleet.parallel(
        fleet.call("research", "search",  Map.of("q", message)),
        fleet.call("analyst",  "analyze", Map.of("q", message))
    );

    // Query the journal for all events involving the research agent
    var events = fleet.journal().query(CoordinationQuery.forAgent("research"));
    for (var event : events) {
        switch (event) {
            case CoordinationEvent.AgentCompleted e ->
                session.stream("research took " + e.duration().toMillis() + "ms");
            case CoordinationEvent.AgentFailed e ->
                session.stream("research failed: " + e.error());
            default -> {}
        }
    }

    session.stream(results.get("analyst").textOr("no result"));
}
```

### CoordinationEvent variants

| Event | Fields |
|-------|--------|
| `CoordinationStarted` | coordinationId, coordinatorName, timestamp |
| `AgentDispatched` | coordinationId, agentName, skill, args, timestamp |
| `AgentCompleted` | coordinationId, agentName, skill, resultText, duration, timestamp |
| `AgentFailed` | coordinationId, agentName, skill, error, duration, timestamp |
| `AgentEvaluated` | coordinationId, agentName, evaluatorName, score, passed, timestamp |
| `AgentActivityChanged` | coordinationId, agentName, activityType, detail, timestamp |
| `CoordinationCompleted` | coordinationId, totalDuration, agentCallCount, timestamp |

`CoordinationQuery` factory methods: `CoordinationQuery.all()`, `CoordinationQuery.forCoordination(id)`, `CoordinationQuery.forAgent(agentName)`. Null fields in a query are wildcards. Set `limit` to cap result count; `0` means unlimited.

### Inspector filtering

Add a `CoordinationJournalInspector` to suppress events before storage:

```java
journal.inspector(event ->
    !(event instanceof CoordinationEvent.AgentDispatched)); // skip dispatch noise
```

The `JournalingAgentFleet` decorator wraps the fleet transparently — all `parallel()`, `pipeline()`, and individual `agent().call()` paths record events automatically.

## Agent Activity Streaming

`AgentActivity` is a sealed interface that models what an agent is doing right now. `DefaultAgentProxy` emits activity transitions at key lifecycle points — `Thinking` before dispatch, `Executing` during tool calls, `Retrying` during backoff, and `Completed`/`Failed` after the call resolves. Listeners receive these transitions in real time on the calling thread.

`StreamingActivityListener` converts each `AgentActivity` variant into an `AiEvent.AgentStep` event and delivers it to the client via `StreamingSession.emit()`. This means clients receive live agent progress over the same WebSocket/SSE connection without any additional wiring — the existing event pipeline (interceptors, filters, AG-UI bridge) processes activity events automatically.

Wire per-session streaming by calling `fleet.withActivityListener()` at the top of a `@Prompt` method:

```java
@Prompt
public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
    var liveFleet = fleet.withActivityListener(new StreamingActivityListener(session));

    var results = liveFleet.parallel(
        liveFleet.call("research", "web_search", Map.of("query", message)),
        liveFleet.call("analyst",  "analyze",    Map.of("query", message))
    );

    session.stream(results.get("analyst").textOr("no result"));
}
```

`withActivityListener()` returns a new fleet instance scoped to this call — the original fleet is unchanged and other concurrent sessions are unaffected.

Clients receive `agent-step` JSON events as agents progress:

```json
{"event":"agent-step","data":{"stepName":"thinking","description":"Agent 'research' is thinking...","data":{"agent":"research","skill":"web_search"}},"sessionId":"...","seq":1}
{"event":"agent-step","data":{"stepName":"completed","description":"Agent 'research' completed in 62ms","data":{"agent":"research","durationMs":62,"skill":"web_search"}},"sessionId":"...","seq":2}
```

### AgentActivity variants

| Variant | Fields | stepName on wire |
|---------|--------|-----------------|
| `Idle` | agentName, since | (not emitted) |
| `Thinking` | agentName, skill, since | `thinking` |
| `Executing` | agentName, skill, detail, since | `executing` |
| `WaitingForInput` | agentName, reason, since | `waiting-for-input` |
| `Retrying` | agentName, skill, attempt, maxAttempts, nextAttemptAt | `retrying` |
| `CircuitOpen` | agentName, reason, cooldownUntil | `circuit-open` |
| `Completed` | agentName, skill, elapsed | `completed` |
| `Failed` | agentName, skill, error, elapsed | `failed` |

`AgentActivityListener` is a `@FunctionalInterface` and is also discoverable via `ServiceLoader` — add it to `META-INF/services/org.atmosphere.coordinator.fleet.AgentActivityListener` to attach a global listener to all fleet instances. Per-session listeners registered via `withActivityListener()` stack on top of any ServiceLoader-discovered listeners.

## Result Evaluation

`ResultEvaluator` implementations are discovered via `ServiceLoader`. When journaling is active, `JournalingAgentFleet` auto-evaluates every successful agent result on a virtual thread and records an `AgentEvaluated` event. You can also call `fleet.evaluate()` explicitly to gate on quality scores synchronously.

```java
public class LengthEvaluator implements ResultEvaluator {

    @Override
    public Evaluation evaluate(AgentResult result, AgentCall originalCall) {
        var wordCount = result.text().split("\\s+").length;
        if (wordCount >= 50) {
            return Evaluation.pass(Math.min(1.0, wordCount / 200.0),
                    "Response has " + wordCount + " words");
        }
        return Evaluation.fail(wordCount / 50.0,
                "Too short: " + wordCount + " words (minimum 50)");
    }
}
```

Register via `META-INF/services/org.atmosphere.coordinator.evaluation.ResultEvaluator`.

Calling `evaluate()` explicitly in a `@Prompt` method:

```java
@Prompt
public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
    var call   = fleet.call("drafter", "draft", Map.of("topic", message));
    var result = fleet.agent("drafter").call("draft", Map.of("topic", message));

    var evals = fleet.evaluate(result, call);
    var passed = evals.stream().allMatch(Evaluation::passed);

    if (passed) {
        session.stream(result.text());
    } else {
        var reason = evals.stream()
                .filter(e -> !e.passed())
                .map(Evaluation::reason)
                .findFirst().orElse("quality check failed");
        session.error(new IllegalStateException(reason));
    }
}
```

`Evaluation` is a record: `score` (0.0–1.0), `passed`, `reason`, `metadata`. `Evaluation.pass(score, reason)` and `Evaluation.fail(score, reason)` are convenience factories.

## Remote Agents via Environment Variables

Transport is resolved automatically at startup. If an agent is registered in the same JVM, `LocalAgentTransport` is used (in-process, no HTTP). If the agent is remote, set an environment variable or system property:

```bash
# Environment variable (AGENT_<NAME>_URL, uppercase, hyphens become underscores)
export AGENT_FINANCE_URL=https://finance-agent.internal/a2a

# System property alternative
-Datmosphere.fleet.agents.finance.url=https://finance-agent.internal/a2a
```

```java
@Coordinator(name = "ceo")
@Fleet({
    @AgentRef(type = ResearchAgent.class),         // local — resolved in-JVM
    @AgentRef(value = "finance", required = false)  // remote — reads AGENT_FINANCE_URL
})
public class CeoCoordinator { ... }
```

When the env var is set, `A2aAgentTransport` is used: JSON-RPC 2.0 over HTTP for `call()`, SSE streaming for `stream()`, with automatic fallback to synchronous if streaming fails.

Optional agents (`required = false`) allow the coordinator to start even when the remote endpoint is unreachable. `fleet.available()` returns only currently reachable agents.

## Fleet Topology Logging

At startup, `CoordinatorProcessor` logs the resolved fleet:

```
Coordinator 'ceo' registered (v1.0.0, fleet: 2 agents, protocols: [a2a, mcp])
  ceo (v1.0.0)
  +-- research        (local, v1.0.0, weight=1, required)  [ResearchAgent]
  +-- finance         (remote, v2.0.0, weight=1, optional)
```

Circular fleet dependencies (coordinator A manages coordinator B which manages A) are detected at startup and fail with a clear `IllegalStateException`.

## Key Components

| Class / Interface | Description |
|-------------------|-------------|
| `@Coordinator` | Marks a class as a coordinator; sets name, skill file, description, and version |
| `@Fleet` | Declares the set of agents this coordinator manages |
| `@AgentRef` | Reference to a single agent by class (`type`) or name (`value`); carries version, required, weight |
| `AgentFleet` | Injected into `@Prompt` methods; provides `agent()`, `agents()`, `available()`, `call()`, `parallel()`, `pipeline()`, `evaluate()`, `journal()` |
| `AgentProxy` | Proxy to a single agent; exposes `call()`, `callAsync()`, `stream()`, `isAvailable()`, `isLocal()`, `weight()` |
| `AgentCall` | Immutable record: pending call spec (`agentName`, `skill`, `args`) |
| `AgentResult` | Immutable record: `agentName`, `skillId`, `text`, `metadata`, `duration`, `success`; `textOr(fallback)` and `failure()` factory |
| `DefaultAgentFleet` | Default `AgentFleet` — parallel fan-out on virtual threads, pipeline with abort-on-failure |
| `CoordinatorProcessor` | `@AtmosphereAnnotation` processor; resolves fleet, wires journal/evaluators, registers protocol bridges |
| `CoordinationJournal` | SPI for execution journaling; `NOOP` constant when not active |
| `CoordinationEvent` | Sealed event hierarchy: `CoordinationStarted`, `AgentDispatched`, `AgentCompleted`, `AgentFailed`, `AgentEvaluated`, `CoordinationCompleted` |
| `CoordinationQuery` | Record for filtering journal queries; factory methods `all()`, `forCoordination()`, `forAgent()` |
| `CoordinationJournalInspector` | Hook to filter events before recording; returning `false` discards the event |
| `InMemoryCoordinationJournal` | Thread-safe in-memory journal (`ConcurrentHashMap` + `CopyOnWriteArrayList`) |
| `JournalingAgentFleet` | Transparent `AgentFleet` decorator; records events and triggers auto-evaluation |
| `ResultEvaluator` | SPI for quality assessment; ServiceLoader-discovered |
| `Evaluation` | Record: `score` (0.0–1.0), `passed`, `reason`, `metadata`; `pass()` and `fail()` factories |
| `AgentActivity` | Sealed interface modeling an agent's current state; 8 record variants: `Idle`, `Thinking`, `Executing`, `WaitingForInput`, `Retrying`, `CircuitOpen`, `Completed`, `Failed` |
| `AgentActivityListener` | `@FunctionalInterface` SPI callback for activity state transitions; ServiceLoader-discoverable |
| `StreamingActivityListener` | Bridges `AgentActivity` transitions to `AiEvent.AgentStep` events via `StreamingSession.emit()` |
| `AgentTransport` | SPI for agent-to-agent communication |
| `LocalAgentTransport` | In-JVM transport via reflection on the A2A protocol handler |
| `A2aAgentTransport` | Remote transport: JSON-RPC 2.0 over HTTP, SSE streaming with sync fallback |

## Protocol Bridges

`CoordinatorProcessor` registers additional endpoints when the corresponding module is on the classpath. All detection is automatic — no configuration required.

| Module on classpath | Endpoint | Protocol |
|---------------------|----------|----------|
| `atmosphere-a2a` | `{basePath}/a2a` | A2A JSON-RPC 2.0 |
| `atmosphere-mcp` | `{basePath}/mcp` | Model Context Protocol |
| `atmosphere-agui` | `{basePath}/agui` | AG-UI SSE |
| `atmosphere-channels` | (channel bridge) | Atmosphere Channels |

The base path for a coordinator named `ceo` is `/atmosphere/agent/ceo`. The web endpoint (WebSocket/SSE) is always registered at the base path regardless of which optional modules are present.

## Configuration

Transport resolution uses environment variables and system properties:

```bash
# Remote agent URL — replace AGENT_NAME with uppercase agent name, hyphens as underscores
export AGENT_RESEARCH_URL=https://research.internal/a2a
export AGENT_RISK_ANALYSIS_URL=https://risk.internal/a2a   # hyphen -> underscore
```

```bash
# System property alternative (useful in containers)
-Datmosphere.fleet.agents.research.url=https://research.internal/a2a
```

The coordinator's own LLM settings follow the same environment variables as `atmosphere-ai`:

```bash
export LLM_MODE=remote
export LLM_MODEL=gemini-2.5-flash
export LLM_API_KEY=AIza...
```

See [atmosphere-ai README](../ai/README.md) for the full LLM configuration reference.

## Test Support

The module includes test stubs in `src/test` for exercising coordinator `@Prompt` methods without any infrastructure or LLM dependency.

### StubAgentFleet

Build a fleet with canned responses:

```java
var fleet = StubAgentFleet.builder()
    .agent("weather", "Sunny, 72F in Madrid")
    .agent("activities", "Visit Retiro Park, Prado Museum")
    .build();

// Call your @Prompt method directly
coordinator.onPrompt("What to do in Madrid?", fleet, session);
```

### StubAgentTransport

Builder with predicate matching for fine-grained control:

```java
var transport = StubAgentTransport.builder()
    .when("weather", "Sunny, 72F")
    .when("news", "No news today")
    .when(msg -> msg.startsWith("urgent"), "BREAKING: ...")
    .defaultResponse("I don't know")
    .unavailable()  // simulate unreachable agent
    .build();
```

### StubAgentRuntime

Stub `AgentRuntime` with `priority = Integer.MAX_VALUE` — wins auto-detection in tests:

```java
var runtime = StubAgentRuntime.builder()
    .when("weather", "Sunny and 72F")
    .when(msg -> msg.contains("joke"), "Why did the chicken...")
    .defaultResponse("I don't understand")
    .build();
```

### StubActivityListener

Captures `AgentActivity` events during a test for assertion:

```java
var listener = new StubActivityListener();
var proxy = new DefaultAgentProxy("weather", "1.0.0", 1, true, 2,
                                   transport, List.of(listener));
proxy.call("search", Map.of());

listener.assertTransition("weather", "Thinking", "Completed");
```

`assertTransition(agentName, expectedTypes...)` asserts that the named agent went through exactly the listed variant simple names, in order. `activitiesFor(agentName)` returns the raw list for custom assertions. `clear()` resets state between test cases.

### CoordinatorAssertions

Fluent assertions for `AgentResult`:

```java
CoordinatorAssertions.assertThat(result)
    .succeeded()
    .containsText("Madrid")
    .fromAgent("weather")
    .completedWithin(Duration.ofSeconds(5));
```

## Requirements

- Java 21+
- `atmosphere-runtime` (transitive)
- `atmosphere-ai` (transitive)
- `atmosphere-agent` (transitive)
- Optional: `atmosphere-a2a`, `atmosphere-mcp`, `atmosphere-agui`, `atmosphere-channels`
