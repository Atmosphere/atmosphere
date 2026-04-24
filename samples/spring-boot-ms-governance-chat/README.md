# MS Agent Governance Toolkit × Atmosphere — Chat

> Atmosphere consumes [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) YAML policy artifacts **verbatim**. No translation, no conversion, no "mostly compatible." The MS-schema file bundled here could be dropped straight onto MS's own `PolicyEvaluator` and behave the same way.

This sample is the production-quality evidence for that claim. It ships with a Microsoft-format YAML policy, enforces it on every chat turn, and exposes the decision chain over both the built-in Atmosphere AI console and a wire-compatible `POST /check` HTTP endpoint.

---

## What's in the box

```
samples/spring-boot-ms-governance-chat/
├── pom.xml                                     # atmosphere-spring-boot-starter + atmosphere-admin
├── README.md                                   # this file
└── src/main/
    ├── java/org/atmosphere/samples/springboot/msgovernance/
    │   ├── MsGovernanceChatApplication.java    # @SpringBootApplication entrypoint
    │   ├── MsGovernanceChat.java               # @AiEndpoint("/atmosphere/ms-governance")
    │   └── PoliciesConfig.java                 # loads MS-schema YAML at startup
    └── resources/
        ├── application.yml                     # console-subtitle + console-endpoint
        └── atmosphere-policies.yaml            # MS schema, priority-sorted rule set
```

**Three Java files, two YAML files, zero custom frontend code.** The UI is the Vue chat console shipped with `atmosphere-spring-boot-starter`.

---

## Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-ms-governance-chat
open http://localhost:8090/atmosphere/console/
```

The console header reads *"Microsoft Agent Governance Toolkit — rules-over-context demo"* (from `atmosphere.console-subtitle` in `application.yml`) and auto-connects to the `@AiEndpoint` at `/atmosphere/ms-governance` (from `atmosphere.console-endpoint`).

---

## The policy file — a verbatim MS artifact

```yaml
# src/main/resources/atmosphere-policies.yaml
version: "1.0"
name: ms-governance-demo
description: Demonstrates Atmosphere running Microsoft Agent Governance Toolkit YAML unchanged

rules:
  - name: block-destructive-sql
    condition:
      field: message
      operator: matches
      value: '(?i)\bdrop\s+(table|database)\b'
    action: deny
    priority: 100
    message: "Destructive SQL statements are not permitted in this chat."

  - name: block-legal-escalation
    condition:
      field: message
      operator: contains
      value: "sue us"
    action: deny
    priority: 90
    message: "Legal-threat language is routed to a human agent — the AI cannot handle it."

  - name: block-ssn-shape
    condition:
      field: message
      operator: matches
      value: '\b\d{3}-\d{2}-\d{4}\b'
    action: deny
    priority: 80
    message: "The message looks like it contains a US Social Security number; refusing to process."

defaults:
  action: allow
```

Every field MS defines is supported:

| MS element | What it maps to in Atmosphere |
|---|---|
| `rules[*].condition.field` | `AiRequest` property or metadata entry — see the context-map bridge below |
| `rules[*].condition.operator` | All nine: `eq`, `ne`, `gt`, `lt`, `gte`, `lte`, `in`, `contains`, `matches` |
| `rules[*].condition.value` | Scalar or list (for `in`); regex string for `matches` |
| `rules[*].action` | `allow`, `deny`, `audit` (admit + structured log), `block` (alias for deny) |
| `rules[*].priority` | Integer; rules pre-sorted descending at load time |
| `rules[*].message` | Plumbed directly into the `reason` field of the `Deny` decision the user sees |
| `defaults.action` | Fallback when no rule matches; supports all four actions |

**Context-map bridge** — rule `field:` references map to:

| Context key | Source |
|---|---|
| `message`, `system_prompt`, `model` | `AiRequest` direct fields |
| `user_id`, `session_id`, `agent_id`, `conversation_id` | `AiRequest` direct fields |
| `phase` | `pre_admission` / `post_response` |
| `response` | Accumulated response text (post-response only) |
| *anything else* | `AiRequest.metadata()` entries by exact key — this is how `tool_name`, `token_count`, etc. get exposed |

---

## Try each rule

Open the console at `http://localhost:8090/atmosphere/console/`:

| Prompt | Expected bubble | Operator exercised |
|---|---|---|
| `please DROP TABLE users` | **Error:** *Denied by policy 'ms-governance-demo': Destructive SQL statements are not permitted in this chat.* | `matches` (regex) |
| `I will sue us if this isn't fixed` | **Error:** *Denied by policy 'ms-governance-demo': Legal-threat language is routed to a human agent — the AI cannot handle it.* | `contains` (substring) |
| `my SSN is 123-45-6789` | **Error:** *Denied by policy 'ms-governance-demo': The message looks like it contains a US Social Security number; refusing to process.* | `matches` (regex) |
| `hello, what services do you offer?` | **AI:** *Got it — you said: "hello, what services do you offer?". …* | No rule matched → `defaults.action: allow` |

Every denial surfaces MS's exact `message:` field — no re-phrasing, no editorializing.

---

## HTTP policy-decision endpoint (MS-compatible)

MS's `PolicyProviderHandler` is an ASGI app with three endpoints: `/check`, `/policies`, `/health`. Atmosphere exposes the same shape under `/api/admin/governance/*`. External gateways already pointed at MS's decision provider work against Atmosphere without code changes.

### List active policies

```bash
curl -s http://localhost:8090/api/admin/governance/policies | jq
```

```json
[
  {
    "name": "ms-governance-demo",
    "source": "classpath:atmosphere-policies.yaml",
    "version": "1.0",
    "className": "org.atmosphere.ai.governance.MsAgentOsPolicy"
  }
]
```

### Summary

```bash
curl -s http://localhost:8090/api/admin/governance/summary | jq
```

```json
{ "policyCount": 1, "sources": ["classpath:atmosphere-policies.yaml"] }
```

### Ad-hoc decision (MS `POST /check` wire format)

```bash
curl -s -X POST http://localhost:8090/api/admin/governance/check \
  -H 'content-type: application/json' \
  -d '{"agent_id":"probe","action":"call_tool","context":{"message":"please DROP TABLE logs"}}' \
  | jq
```

```json
{
  "allowed": false,
  "decision": "deny",
  "reason": "Destructive SQL statements are not permitted in this chat.",
  "matched_policy": "ms-governance-demo",
  "matched_source": "classpath:atmosphere-policies.yaml",
  "evaluation_ms": 3.27
}
```

The allowed path:

```bash
curl -s -X POST http://localhost:8090/api/admin/governance/check \
  -H 'content-type: application/json' \
  -d '{"agent_id":"probe","context":{"message":"what is 2+2"}}' \
  | jq
```

```json
{ "allowed": true, "decision": "allow", "reason": "", "matched_policy": null,
  "matched_source": null, "evaluation_ms": 0.08 }
```

---

## How the wiring works

```
atmosphere-policies.yaml
        │
        │  (1) PoliciesConfig @Bean reads it at startup
        ▼
YamlPolicyParser.parse("classpath:atmosphere-policies.yaml", in)
        │
        │  (2) Top-level `rules:` triggers the MS branch
        ▼
MsAgentOsPolicy (priority-sorted, first-match-wins)
        │
        │  (3) Published to GovernancePolicy.POLICIES_PROPERTY
        ▼
framework.getAtmosphereConfig().properties()
        │
        │  (4) AiEndpointProcessor merges on handler registration
        ▼
AiEndpointHandler (receives each @Prompt)
        │
        │  (5) Non-pipeline response path calls PolicyAdmissionGate
        ▼
MsGovernanceChat.onPrompt → PolicyAdmissionGate.admit(resource, request)
        │
        ├── Denied → session.error(SecurityException(policy.reason))
        │      ↓
        │   Built-in console renders as "Error: <message>"
        │
        └── Admitted → session.send(reply), session.complete()
               ↓
           Built-in console streams the reply
```

Steps 3 and 4 also make the same policy list reachable from the admin HTTP endpoints — one source of truth, three consumer surfaces.

---

## Why use the built-in console (and not custom HTML)

Early iterations of this sample shipped a custom chat page. It was −132 lines and one parser bug (WebSocket JSON-envelope) away from being right. The final sample uses the Vue chat console shipped with `atmosphere-spring-boot-starter` because:

- **Zero sample-local UI code.** The console is framework code, tested in CI, consistent across every Atmosphere AI sample.
- **Production-quality streaming.** Markdown rendering, reconnect, transport fallback, timestamp headers — all already there.
- **Frame parsing is correct.** The console speaks Atmosphere's native `{type, data, sessionId, seq}` envelope format; no sample has to re-implement it.
- **One configuration surface.** `application.yml` sets `atmosphere.console-subtitle` + `atmosphere.console-endpoint` and the auto-configured `/api/console/info` endpoint serves that metadata to the Vue app.

Trade-off accepted: the console can't carry inline "try these prompts" hints. That narrative lives here in the README, which is where an engineer evaluating the sample looks anyway.

---

## Where the interop claim is actually verified

- `modules/ai/src/main/java/org/atmosphere/ai/governance/MsAgentOsPolicy.java` — faithful port of MS's `_match_condition` from `agent_os/policies/evaluator.py`. All nine operators, all four actions.
- `modules/ai/src/test/resources/ms-agent-os/` — byte-for-byte copies of MS's example YAMLs from `microsoft/agent-governance-toolkit@April-2026` (`docs/tutorials/policy-as-code/examples/`).
- `modules/ai/src/test/java/org/atmosphere/ai/governance/MsAgentOsYamlConformanceTest.java` — 10 tests that parse those fixtures and assert MS's documented behavior. Upstream MS schema drift fails the build.
- `modules/admin/src/main/java/org/atmosphere/admin/ai/GovernanceController.java` — the `/check` endpoint's MS-compat payload mapping + response shape.

When a claim like "Atmosphere consumes MS YAML artifacts" lands in a blog post or conference talk, the reviewer can point at any of those four files to verify it holds.

---

## Scaffold a new project from this sample

```bash
atmosphere new my-governance-app --template ms-governance
cd my-governance-app
./mvnw spring-boot:run
```

Template handled by the Atmosphere CLI (`cli/atmosphere`). The `--template ms-governance` flag pulls exactly the file layout above so you can drop your own MS-format YAML into `src/main/resources/atmosphere-policies.yaml` and ship.

---

## Further reading

- **In-tree detailed docs**: [`docs/governance-policy-plane.md`](../../docs/governance-policy-plane.md)
- **Module reference**: [`modules/ai/README.md`](../../modules/ai/README.md#governance-policy-plane-phase-a)
- **Tutorial walkthrough**: [atmosphere.github.io — Tutorial 30](https://atmosphere.github.io/docs/tutorial/30-governance-policy-plane/)
- **Reference**: [atmosphere.github.io — Governance](https://atmosphere.github.io/docs/reference/governance/)
- **Upstream toolkit**: [github.com/microsoft/agent-governance-toolkit](https://github.com/microsoft/agent-governance-toolkit)
