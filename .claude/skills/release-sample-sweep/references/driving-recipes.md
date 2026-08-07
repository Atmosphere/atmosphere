# Driving recipes — chrome-devtools per surface class

Every sample falls into one of five surface classes. Find the class in
`sample-matrix.md`, then follow the recipe here.

All test ids below are read from the Console source
(`modules/spring-boot-starter/frontend/src/`). If a selector stops matching,
re-read the component before assuming the sample is broken — a renamed test id
is a spec-maintenance task, not a sample failure.

## Console test-id inventory

| Test id | Component | What it proves |
|---|---|---|
| `atmosphere-connection-status` | `ConnectionStatus.vue` | Carries `data-transport`, `data-phase`, `data-via-fallback` |
| `status-label` | `ConnectionStatus.vue` | Renders `Connected · <transport>`, plus ` · fallback` when the primary transport failed |
| `console-tabs` | `App.vue` | The tab strip — tabs are conditional on what the server exposes |
| `chat-input` / `chat-send` | `ChatInput.vue` | Input placeholder reads `Connecting...` while disabled |
| `message-list` / `message-bubble` | `ChatContainer.vue`, `ChatMessage.vue` | Rendered messages |
| `tool-activity` / `tool-card` | `ChatContainer.vue`, `ToolCard.vue` | A tool actually fired |
| `approval-prompt` / `approval-approve` / `approval-deny` | `ToolCard.vue` | HITL gate intercepted before execution |
| `agent-activity` / `fleet-roster` / `fleet-agent` | `ChatContainer.vue` | Multi-agent coordination surfaced |
| `session-stats` | `ChatContainer.vue` | Token/cost metrics after streaming ends |
| `validation-view`, `goal-input`, `run-check`, `check-result`, `check-status`, `verdict-<name>`, `violation`, `smt-solver` | `Validation.vue` | Plan-and-Verify chain and its verdicts |
| `presence-count`, `offline-queue-size`, `routing-chips` | `ChatContainer.vue` | Presence / offline queue / routing |

---

## Class A — Console chat (the majority)

```
new_page                 http://localhost:<port>/atmosphere/console/
wait_for                 text: "Connected"
take_snapshot            → read data-transport on atmosphere-connection-status
fill                     chat-input ← the prompt from the matrix row
click                    chat-send
wait_for                 the expected answer text
take_snapshot            → confirm it is inside a message-bubble node
list_console_messages
list_network_requests
```

Checks that are easy to skip and matter:

- **Transport.** `status-label` must read the transport the matrix expects. A
  ` · fallback` suffix means the primary transport failed and the client
  degraded — record it. A WebTransport sample silently running on WebSocket is
  a finding, not a pass.
- **The input placeholder.** Still `Connecting...`? The transport never opened;
  everything after that is meaningless.
- **Streaming, not just the final text.** Take a snapshot mid-stream: tokens
  should appear incrementally. A single atomic message means streaming
  collapsed into one frame.
- **`session-stats`** appears once streaming ends — its absence on an AI sample
  is worth a line in the ledger.

## Class B — Console panel / tab

The tab strip is conditional: a tab renders only when the server exposes the
surface behind it. **A missing tab is a finding**, and often the first symptom
of a wiring regression.

```
new_page       http://localhost:<port>/atmosphere/console/
take_snapshot  → confirm the expected tab exists in console-tabs
click          the tab
...            drive the panel
```

- **Validation** (`guarded-email-agent`): `goal-input` ← a malicious goal,
  `run-check`, then assert `check-status` is a refusal and a `verdict-*` /
  `violation` node names the verifier that refused. The point is that it
  refuses **before any tool fires** — confirm no `tool-card` appeared.
- **Checkpoints** (`checkpoint-agent`): drive a chat turn first, then assert a
  new durable checkpoint row exists.
- **Interactions** (`coding-agent`): launch a run, assert it reaches COMPLETED
  with its step + metadata. Cross-check `/api/interactions`.
- **Tape** (`multi-agent-startup-team`): needs
  `-Datmosphere.admin.content-read-auth-required=false`; without it the read
  gate answers 401 by design — that is correct behaviour, not a bug.

## Class C — Admin dashboard (`spring-boot-admin-bundle`)

```
new_page       http://localhost:<port>/atmosphere/admin/
wait_for       the dashboard shell
take_snapshot  → event stream connected, broadcaster count, runtime name
```

Runtime truth applies: the runtime and counts must be resolved from the running
framework. A hardcoded-looking constant is a finding.

## Class D — Headless wire protocols

`a2a-agent`, `mcp-server`, `passivation-agent`, `reattach-harness`,
`kotlin-dsl-chat` serve no HTML. Driving them over their wire protocol is the
correct analog to browser-driving — this is **not** a licence to validate UI
samples with curl.

- **A2A**: `GET /.well-known/agent.json` for the Agent Card, then a
  `message/send` JSON-RPC call; assert `TASK_STATE_COMPLETED` **and** a real
  artifact in the response.
- **MCP**: `initialize`, `tools/list`, `tools/call` over streamable HTTP;
  assert the payload is runtime-resolved.
- **passivation-agent**: `POST /api/agent/pause` → `GET /checkpoints/{id}` →
  `POST /api/agent/resume`; assert history restored and `continued:true`.
- **reattach-harness**: trigger a synthetic run, reconnect with the run id,
  assert **all** buffered events replay in order (the server logs `replayed N/N`).
- **kotlin-dsl-chat**: subscribe over WebSocket, `POST` `ping`, assert `pong`
  arrives on the subscription. Also check the server log for SLF4J NOP-logger
  lines — the logging defect and the delivery defect were separate bugs.

**Do not browser-drive a raw `ws://localhost` from `about:blank`.** Chrome's
Private Network Access blocks it from an opaque origin; you need a served
same-origin page. That is why these are wire-driven.

## Class E — Rooms / multi-client (`ai-classroom`)

```
new_page       http://localhost:<port>/atmosphere/console/
click          Rooms tab → join a room
take_snapshot  → confirm the room joined and the transport badge
...            drive a prompt, assert the shared stream is delivered
```

For a genuine multi-client assertion, open a **second page** on the same room
and confirm the stream lands in both. Keep them as separate pages
(`list_pages` / `select_page`), never one page navigated twice.

---

## Evidence collection (every sample, every class)

```
list_console_messages          → every error and warning, verbatim
list_network_requests          → any non-2xx or failed request, incl. the WS upgrade
sweep-sample.sh warnings <s>   → server-side WARN/ERROR/exception/SLF4J
take_screenshot                → for the report, on anything not a plain PASS
```

Record warnings even for passing samples. The warning inventory is what the
next sweep's triage starts from, and a warning that appears on 20 samples is a
framework finding hiding as noise.

## Traps that produce false results

| Trap | Symptom | Handling |
|---|---|---|
| Reusing a page across samples | Console errors from the previous sample; stale session | `close_page` after every sample; `new_page` for the next |
| Asserting payload, not render | Base64 sits in a `StaticText` node | Check the node **type** — `image` with a `src` is rendered; text is not |
| `wait_for` matching too early | Matches a prefix mid-stream | Wait for a terminal marker, then re-snapshot |
| Probing a long-poll endpoint | The probe hangs | Probe `/atmosphere/console/` or `/`, never a suspended endpoint |
| Silent transport fallback | Everything "works" | Read `data-transport` and the ` · fallback` suffix |
| Rapid-fire prompts | Errors that look like framework bugs | Local models serialize; give one turn time to finish |
| Judging a small model's tool call | Ollama 400 / "stuck in node" | Model limitation — re-run on a capable model before calling it a regression |
