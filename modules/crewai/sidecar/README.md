# atmosphere-crewai-bridge

Python sidecar speaking the Atmosphere CrewAI runtime wire protocol
(HTTP + SSE). Pair with the Java `atmosphere-crewai` module to bring a
[CrewAI](https://www.crewai.com/) multi-agent crew into a Java /
Spring Boot / Quarkus service without rewriting the agent logic.

License: Apache 2.0 (matches the rest of the Atmosphere project).

## Status

Ships text streaming, token usage, cancellation, system-prompt
injection, and Java→Python tool RPC. The Java-side runtime declares
`TOOL_CALLING`, `SYSTEM_PROMPT`, `STRUCTURED_OUTPUT`, `TOOL_APPROVAL`,
`AGENT_ORCHESTRATION`, `CANCELLATION`, `PER_REQUEST_RETRY`,
`TEXT_STREAMING`, `TOKEN_USAGE` (9 capabilities pinned by
`CrewAiRuntimeContractTest`). `CONVERSATION_MEMORY` stays inside the
sidecar's crew rather than being declared at the Atmosphere layer.

## Install

From source (the recommended path until the package is on PyPI):

```bash
cd modules/crewai/sidecar
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
```

Or with `pipx` (isolated runtime, recommended for production use):

```bash
pipx install ./modules/crewai/sidecar
```

The package requires Python 3.10+. CrewAI 1.14+ is pulled in as a
hard dependency.

## Run

```bash
atmosphere-crewai-bridge \
    --host 127.0.0.1 \
    --port 8765 \
    --crew my_crew:make_crew
```

Then point the Java side at it:

```bash
export ATMOSPHERE_CREWAI_SIDECAR_URL=http://127.0.0.1:8765
./mvnw spring-boot:run -pl samples/spring-boot-ai-chat
```

The Java runtime's `isAvailable()` flips to `true` as soon as the
sidecar's `/health` endpoint responds 200.

## Configuration

Standard CrewAI configuration applies — anything CrewAI reads from
environment variables (OpenAI keys, model defaults, telemetry knobs)
flows through unchanged. Set them in the process that launches the
bridge:

| Variable          | Purpose                                |
|-------------------|----------------------------------------|
| `OPENAI_API_KEY`  | Required for OpenAI-backed crews       |
| `ANTHROPIC_API_KEY` | Required for Anthropic-backed crews  |
| `CREWAI_TELEMETRY_OPT_OUT` | Set to `1` to silence CrewAI's analytics ping |

The bridge itself does not read environment variables today —
everything is passed via CLI flags. This is deliberate so the
operational surface stays small.

## Crew factory contract

The `--crew` argument is a `module.path:attribute` spec. The attribute
must resolve to ONE of:

1. **A callable** with one of the following signatures:

   ```python
   def make_crew() -> crewai.Crew: ...
   def make_crew(message: str) -> crewai.Crew: ...
   def make_crew(message: str, history: list[dict]) -> crewai.Crew: ...
   ```

   The bridge inspects the signature and calls with the matching
   arity. `history` is a list of `{"role": "...", "content": "..."}`
   dicts.

2. **A pre-built `crewai.Crew` instance** — used as-is across all
   sessions. Useful for stateless crews.

The crew's tasks should reference the prompt via standard CrewAI
template interpolation. The bridge calls `crew.kickoff_async(inputs={...})`
where `inputs` always contains:

* `message` — the user's prompt (as sent in `POST /v1/sessions`)
* `history` — rendered as a single string (`"role: content"` joined by
  newlines) for direct splicing into a task description
* `model` — present only when the request specified a `model` field

### Example: `my_crew.py`

```python
from crewai import Agent, Crew, Task, Process

researcher = Agent(
    role="Researcher",
    goal="Find concise, accurate answers",
    backstory="A meticulous research assistant.",
    verbose=False,
)

answer = Task(
    description="Answer the user's question: {message}",
    expected_output="A short, well-sourced answer.",
    agent=researcher,
)

def make_crew():
    return Crew(
        agents=[researcher],
        tasks=[answer],
        process=Process.sequential,
        verbose=False,
        stream=True,  # opt in to streaming so tokens flow to the wire
    )
```

Run with:

```bash
atmosphere-crewai-bridge --crew my_crew:make_crew
```

### Working example — `examples/ollama_crew.py`

The repository ships a one-agent factory wired to local Ollama at
`modules/crewai/sidecar/examples/ollama_crew.py`. It targets
`qwen2.5:0.5b` (~400 MB, no API key needed) so a fresh checkout can run
the full e2e roundtrip:

```bash
# 1. Ollama
ollama pull qwen2.5:0.5b
ollama serve &

# 2. Sidecar
cd modules/crewai/sidecar
source .venv/bin/activate  # or pip install -e .
PYTHONPATH=. atmosphere-crewai-bridge --crew examples.ollama_crew:make_crew

# 3. Any Atmosphere app with atmosphere-crewai on the classpath
ATMOSPHERE_CREWAI_SIDECAR_URL=http://127.0.0.1:8765 \
  ./mvnw -pl <your-sample> spring-boot:run
```

This is the exact path the in-repo chrome-devtools validation drives,
so it's a known-good reference shape for new sidecar consumers.

## Streaming behavior

CrewAI 1.14+ exposes a first-class streaming output: setting
`crew.stream = True` and calling `kickoff_async()` returns a
`CrewStreamingOutput` that yields `StreamChunk` objects as the LLM
produces tokens. The bridge forwards each chunk's `content` as one
`event: token` frame.

If the crew object doesn't expose `kickoff_async` / `stream` (older
CrewAI versions, or a user-built crew shim), the bridge falls back to
`crew.kickoff()` and emits a single `event: token` frame containing
the full `CrewOutput.raw` text. From the Java transport's
perspective that's still streaming — the SSE framing is identical and
the runtime calls `session.send()` once per token frame.

Token usage is read from `CrewOutput.token_usage.{prompt,completion,total}_tokens`
and emitted as a single `event: usage` frame after the last token.
The Java side maps these to `ai.tokens.input`, `ai.tokens.output`,
`ai.tokens.total` on the streaming session.

## Cancellation

`DELETE /v1/sessions/{id}` sets a per-session cancel event. The next
chunk read from the CrewAI streaming output sees the flag, calls
`aclose()` on the underlying iterator (so CrewAI can tear down its
in-flight LLM call), and emits an `event: error` frame with
`{"message": "cancelled"}`. The DELETE returns 204 regardless of
whether the session existed — it is idempotent per the wire-protocol
contract.

## Wire protocol

Documented in [`../README.md`](../README.md) under "Sidecar wire
protocol". The Java side's `HttpSseSidecarClient` is the canonical
consumer; the embedded test fixture
`CrewAiAgentRuntimeBridgeTest$FakeSidecar` is a parallel reference
implementation in ~100 lines of Java.

## Tests

The Python side ships with a test suite that exercises the FastAPI
surface against a `FakeCrew` stand-in (no LLM calls, no network):

```bash
source .venv/bin/activate
python -m pytest tests/ -v
```

The Java side has its own 8-test suite (`CrewAiAgentRuntimeBridgeTest`)
that drives the runtime against an embedded HTTP server speaking the
same wire shape. Together the two suites pin the protocol from both
ends.

## What's NOT in this package

* **Structured output.** CrewAI's `output_pydantic` / `output_json`
  knobs are not bridged yet.
* **Conversation memory checkpoint.** History is forwarded on every
  start, but there's no sidecar-side checkpoint contract today.

All gaps are tracked in the Java module's capability inventory
(`modules/crewai/README.md`).
