# @Coordinator Reference

`@Coordinator` marks a class as an agent that manages a fleet of other agents. It subsumes `@Agent` -- the `CoordinatorProcessor` handles base agent setup internally, then adds fleet wiring. A coordinator has its own AI pipeline and can delegate work to fleet agents via `AgentFleet`.

## @Coordinator Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | (required) | Coordinator name. Used in the path and protocol metadata. |
| `skillFile` | `String` | `""` | Classpath resource for the skill file (system prompt). |
| `description` | `String` | `""` | Human-readable description for Agent Card metadata. |
| `version` | `String` | `"1.0.0"` | Version for Agent Card metadata. |
| `responseAs` | `Class<?>` | `Void.class` | Target type for structured LLM output. |
| `journalFormat` | `Class<? extends JournalFormat>` | `JournalFormat.class` (disabled) | Journal format to auto-emit after `@Prompt` completes. |

## @Fleet and @AgentRef

`@Fleet` declares the set of agents managed by the coordinator. Applied at class level alongside `@Coordinator`.

```java
@Coordinator(name = "ceo", skillFile = "prompts/ceo-skill.md")
@Fleet({
    @AgentRef(type = ResearchAgent.class),
    @AgentRef(value = "finance", version = "2.0.0"),
    @AgentRef(value = "analytics", required = false, maxRetries = 2)
})
public class CeoCoordinator { ... }
```

### @AgentRef Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String` | `""` | Agent name (for remote or cross-module references). Must match `@Agent(name=...)`. |
| `type` | `Class<?>` | `void.class` | Agent class (compile-safe, local agents). Exactly one of `value` or `type` required. |
| `version` | `String` | `""` | Expected version. Advisory -- logged and warned, not enforced. |
| `required` | `boolean` | `true` | If `false`, coordinator starts even if this agent is unavailable. |
| `weight` | `int` | `1` | Preference weight for routing. Reserved for future load-balancing. |
| `maxRetries` | `int` | `0` | Max retry attempts on transient failures. Exponential backoff from 100ms. |

**Type-safe references** resolve the agent name from the class's `@Agent(name=...)` annotation at startup:

```java
@AgentRef(type = ResearchAgent.class)  // compile-safe, IDE navigation
@AgentRef(value = "finance")           // name-based, works for remote agents
```

## AgentFleet API

`AgentFleet` is injected into `@Prompt` methods of `@Coordinator` classes.

```java
@Prompt
public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
    var research = fleet.agent("research").call("web_search", Map.of("query", message));
    session.stream("Synthesize: " + research.text());
}
```

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `agent(String name)` | `AgentProxy` | Get a proxy to a named agent. Throws if not found. |
| `agents()` | `List<AgentProxy>` | All agents declared in `@Fleet`. |
| `available()` | `List<AgentProxy>` | Currently available agents (filters out unavailable optional agents). |
| `call(agentName, skill, args)` | `AgentCall` | Build a call spec without executing. |
| `parallel(AgentCall...)` | `Map<String, AgentResult>` | Execute calls in parallel. Results keyed by agent name. |
| `pipeline(AgentCall...)` | `AgentResult` | Execute calls sequentially. Returns the final result. |
| `evaluate(result, originalCall)` | `List<Evaluation>` | Run all registered `ResultEvaluator`s against a result. |
| `journal()` | `CoordinationJournal` | Access the coordination journal. Returns `NOOP` if journaling is inactive. |

### AgentProxy

Returned by `fleet.agent(name)`. Encapsulates transport (local or remote).

| Method | Returns | Description |
|--------|---------|-------------|
| `name()` | `String` | Agent name |
| `version()` | `String` | Agent version |
| `isAvailable()` | `boolean` | Whether the agent is reachable |
| `isLocal()` | `boolean` | `true` if in-JVM transport |
| `weight()` | `int` | Preference weight |
| `call(skill, args)` | `AgentResult` | Synchronous invocation |
| `callAsync(skill, args)` | `CompletableFuture<AgentResult>` | Async invocation |
| `stream(skill, args, onToken, onComplete)` | `void` | Streaming invocation |

### AgentResult

Record returned by agent calls.

| Field | Type | Description |
|-------|------|-------------|
| `agentName` | `String` | Name of the responding agent |
| `skillId` | `String` | Skill that was invoked |
| `text` | `String` | Response text |
| `metadata` | `Map<String, Object>` | Additional metadata |
| `duration` | `Duration` | Execution time |
| `success` | `boolean` | Whether the call succeeded |

Utility: `textOr(String fallback)` returns `text` on success, `fallback` on failure.

### AgentCall

Record built by `fleet.call()`, executed by `parallel()` or `pipeline()`.

| Field | Type | Description |
|-------|------|-------------|
| `agentName` | `String` | Target agent |
| `skill` | `String` | Skill to invoke |
| `args` | `Map<String, Object>` | Call arguments (immutable copy) |

## Transport

Transport is auto-detected per agent at startup:

| Transport | When Used | Mechanism |
|-----------|-----------|-----------|
| `LocalAgentTransport` | Agent is in the same JVM (has a registered A2A handler) | Direct method invocation via `LocalDispatchable`. No HTTP. |
| `A2aAgentTransport` | Agent is remote (URL-based `@AgentRef`) | A2A JSON-RPC 2.0 over HTTP (`java.net.http.HttpClient`). |

## Coordination Journal

The `CoordinationJournal` SPI records the execution graph of agent coordinations. Pluggable via `ServiceLoader`.

### Event Types (sealed interface)

| Event | Fields | Description |
|-------|--------|-------------|
| `CoordinationStarted` | `coordinatorName` | Coordination begins |
| `AgentDispatched` | `agentName`, `skill`, `args` | Call sent to agent |
| `AgentCompleted` | `agentName`, `skill`, `resultText`, `duration` | Agent returned successfully |
| `AgentFailed` | `agentName`, `skill`, `error`, `duration` | Agent call failed |
| `AgentEvaluated` | `agentName`, `evaluatorName`, `score`, `passed` | Evaluation result recorded |
| `CoordinationCompleted` | `totalDuration`, `agentCallCount` | Coordination finished |

All events carry `coordinationId` and `timestamp`.

### JournalFormat

Controls how events are rendered to text. Set via `@Coordinator(journalFormat = ...)`.

| Format | Class | Output |
|--------|-------|--------|
| Standard log | `JournalFormat.StandardLog` | One line per event using `toLogLine()` |
| Markdown | `JournalFormat.Markdown` | Table with Event, Agent, Detail, Duration columns |
| Custom | Implement `JournalFormat` | `String format(List<CoordinationEvent> events)` |

```java
@Coordinator(name = "ceo", journalFormat = JournalFormat.Markdown.class)
```

When `journalFormat` is set, the framework renders the journal after `@Prompt` completes and emits it as a `ToolStart`/`ToolResult` event pair.

### Journal API

```java
journal.record(event);                          // Record an event
journal.retrieve(coordinationId);               // All events for one coordination
journal.query(CoordinationQuery.all());         // Query with filter
journal.formatLog();                            // Render with STANDARD_LOG
journal.formatLog(JournalFormat.MARKDOWN);       // Render with Markdown
journal.inspector(inspector);                   // Add pre-record filter
```

## Result Evaluation

`ResultEvaluator` is an SPI for post-execution quality assessment. Implementations are discovered via `ServiceLoader`.

```java
public interface ResultEvaluator {
    Evaluation evaluate(AgentResult result, AgentCall originalCall);
    default String name() { return getClass().getSimpleName(); }
}
```

`Evaluation` is a record: `score` (0.0-1.0), `passed`, `reason`, `metadata`.

Evaluators run automatically (async, recorded in journal) after each agent call and can also be invoked explicitly via `fleet.evaluate(result, call)`.

## Test Support

The `org.atmosphere.coordinator.test` package provides stubs for testing `@Prompt` methods without infrastructure.

### StubAgentFleet

```java
var fleet = StubAgentFleet.builder()
    .agent("weather", "Sunny, 72F in Madrid")
    .agent("activities", "Visit Retiro Park")
    .build();

coordinator.onPrompt("What to do in Madrid?", fleet, session);
```

### StubAgentTransport

```java
var transport = StubAgentTransport.builder()
    .when("weather", "Sunny, 72F")
    .when("news", "No news today")
    .defaultResponse("I don't know")
    .build();
```

Supports predicate-based matching, custom `AgentResult` responses, and `.unavailable()` to simulate unreachable agents.

## Circular Dependency Detection

The `CoordinatorProcessor` detects circular fleet dependencies at startup. If coordinator A references coordinator B which references coordinator A (directly or transitively), an `IllegalStateException` is thrown with the dependency path.
