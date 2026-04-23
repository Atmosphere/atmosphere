# Governance Policy Plane

**Status:** Shipped. All SPI + YAML parity + HTTP endpoint + sample are in-tree and covered by tests.

**Module:** `atmosphere-ai`. Admin-surface pieces live in `atmosphere-admin` and the Spring Boot auto-configuration is in `atmosphere-spring-boot-starter`.

**One-line summary:** declarative governance policies — admit / deny / transform every AI turn — loaded from YAML (Atmosphere-native schema OR Microsoft Agent Governance Toolkit schema verbatim), enforced on every `@AiEndpoint`, introspected through the admin console, and queryable via a Microsoft-compatible `POST /check` decision endpoint.

---

## Why this exists

Atmosphere has had `AiGuardrail` from the beginning — PII redaction, cost ceilings, output drift detection — but every guardrail was imperative Java code. The policy plane layers a declarative identity-carrying SPI on top so that:

1. **Operators can author governance in YAML**, not Java. Change `atmosphere-policies.yaml`, restart, governance posture changes. No recompilation.
2. **Audit trails are pinned to policy identity**. Every admit / deny / transform decision records the `name`, `source` URI, and `version` of the matching policy — not just "some guardrail fired."
3. **The vocabulary matches external ecosystems.** `admit` / `deny` / `transform` lines up with OPA/Rego and the Microsoft Agent Governance Toolkit. An operator fluent in either toolchain can read an Atmosphere policy without re-learning the model.
4. **Existing `AiGuardrail` implementations keep working.** The SPI is strictly additive; adapters bridge the two directions.

---

## Architecture

```
                     ┌──────────────────────────────────────────────────────────┐
                     │                     atmosphere-policies.yaml             │
                     │   (Atmosphere-native `policies:` OR MS `rules:`)         │
                     └────────────────────────────┬─────────────────────────────┘
                                                  │
                         YamlPolicyParser auto-detects schema
                                                  │
                     ┌────────────────────────────▼─────────────────────────────┐
                     │     List<GovernancePolicy>                               │
                     │  • PolicyRegistry type-dispatch (pii-redaction, etc.)    │
                     │  • MsAgentOsPolicy (rules-over-context)                  │
                     └────────────────────────────┬─────────────────────────────┘
                                                  │
                  framework.getAtmosphereConfig()
                    .properties()
                    .put(POLICIES_PROPERTY, policies)
                                                  │
    ┌─────────────────────────────────────────────┼────────────────────────────────┐
    │                                             │                                │
    ▼                                             ▼                                ▼
AiEndpointProcessor                     PolicyAdmissionGate              GovernanceController
 (for @AiEndpoint                          (for non-pipeline                (admin HTTP surface)
  handlers; wraps                           code paths like                     │
  policies via                              demo responders)                    │
  PolicyAsGuardrail                            │                                │
  into the guardrail                           │                                │
  list)                                        │                                │
    │                                          │                                │
    ▼                                          ▼                                ▼
AiPipeline.execute                    PolicyAdmissionGate.Result       GET /api/admin/governance/policies
 pre-admission loop → Deny aborts     (caller matches Admit/Denied)    GET /api/admin/governance/summary
 Transform rewrites request                                            POST /api/admin/governance/check
 response-side reuses                                                   (MS `PolicyProviderHandler`-compatible)
 GuardrailCapturingSession
```

Three consumer surfaces, one policy list. Every surface reads the same `POLICIES_PROPERTY` bag, so Spring beans, ServiceLoader entries, and YAML files all converge on the same enforcement chain.

---

## Concepts

### `GovernancePolicy` SPI

`org.atmosphere.ai.governance.GovernancePolicy` — the declarative SPI:

```java
public interface GovernancePolicy {
    String POLICIES_PROPERTY = "org.atmosphere.ai.governance.policies";

    String name();      // stable identity for audit trail
    String source();    // yaml:/path/file.yaml | classpath:file.yaml | code:<fqn>
    String version();   // semver | ISO date | sha256:… — operator choice

    PolicyDecision evaluate(PolicyContext context);
}
```

`PolicyContext` carries the phase (`PRE_ADMISSION` / `POST_RESPONSE`), the `AiRequest`, and the accumulated response text. `PolicyDecision` is a sealed type: `Admit`, `Transform(modifiedRequest)`, `Deny(reason)`.

Implementations must be thread-safe, side-effect-free (except for metrics/logging), and MUST NOT throw — exceptions fail-closed to Deny at every admission seam.

### `PolicyParser` SPI

`org.atmosphere.ai.governance.PolicyParser` — parse a declarative artifact into `List<GovernancePolicy>`. Discovered via `java.util.ServiceLoader`. One implementation ships in-tree:

- **`YamlPolicyParser`** (`format() = "yaml"`) — SnakeYAML `SafeConstructor` (no arbitrary class instantiation). Auto-detects Atmosphere-native vs Microsoft Agent Governance Toolkit schema by inspecting the root keys.

Additional parsers (Rego, Cedar, etc.) plug in by shipping a `PolicyParser` implementation plus a `META-INF/services/org.atmosphere.ai.governance.PolicyParser` entry.

### `PolicyRegistry` and built-in types

`org.atmosphere.ai.governance.PolicyRegistry` maps YAML `type:` names to factory functions. Three built-in types ship:

| `type:` | Wraps | Config keys |
|---|---|---|
| `pii-redaction` | `PiiRedactionGuardrail` | `mode: redact \| block` |
| `cost-ceiling` | `CostCeilingGuardrail` | `budget-usd: <number>` |
| `output-length-zscore` | `OutputLengthZScoreGuardrail` | `window-size`, `z-threshold`, `min-samples` |

Register a custom type in code:

```java
var registry = new PolicyRegistry();
registry.register("my-domain-policy", descriptor ->
        new MyDomainPolicy(descriptor.name(), descriptor.source(),
                descriptor.version(), descriptor.config()));
var parser = new YamlPolicyParser(registry);
```

### `PolicyAdmissionGate`

`org.atmosphere.ai.governance.PolicyAdmissionGate` — utility that runs the policy chain on an `AiRequest` **outside the pipeline**. Exists because some `@Prompt` handlers respond locally (demo producers, canned responders) and therefore never invoke `AiPipeline.execute`. Without the gate those paths would bypass governance entirely — the classroom sample was the first casualty.

```java
var gate = PolicyAdmissionGate.admit(resource, new AiRequest(message));
switch (gate) {
    case PolicyAdmissionGate.Result.Denied denied ->
            session.error(new SecurityException("Denied by " + denied.policyName()));
    case PolicyAdmissionGate.Result.Admitted admitted ->
            // forward admitted.request().message() to your local responder
}
```

### `GuardrailAsPolicy` and `PolicyAsGuardrail`

Interop adapters so the imperative and declarative layers share one enforcement seam:

- `GuardrailAsPolicy` wraps any `AiGuardrail` as a `GovernancePolicy` with default identity (`code:<fqn>`, version `embedded`) or explicit identity.
- `PolicyAsGuardrail` wraps any `GovernancePolicy` as an `AiGuardrail`. Used internally by `AiEndpointProcessor` to merge policies into the guardrail list consumed by `AiPipeline`. `Transform` decisions on the post-response path are downgraded to `Pass` with a warning (streamed text is not retroactively rewritable).

---

## YAML schemas

### Atmosphere-native (type-dispatch)

```yaml
version: "1.0"
policies:
  - name: customer-pii-guard
    type: pii-redaction
    version: "1.0"
    config:
      mode: redact            # redact | block

  - name: drift-watcher
    type: output-length-zscore
    config:
      window-size: 50
      z-threshold: 3.0
      min-samples: 10

  - name: tenant-budget
    type: cost-ceiling
    config:
      budget-usd: 100.00
```

Each entry maps to a built-in type and gets wrapped as a `GuardrailAsPolicy` over the corresponding guardrail.

### Microsoft Agent Governance Toolkit (rules-over-context)

`YamlPolicyParser` auto-detects the MS schema — documents with a top-level `rules:` sequence — and produces a single `MsAgentOsPolicy` that preserves MS's first-match-by-priority evaluation semantic verbatim. All nine comparison operators and all four actions are supported:

```yaml
version: "1.0"
name: production-policy
description: Company-wide policy — verbatim example from MS's docs
rules:
  - name: block-delete-database
    condition: { field: tool_name, operator: eq, value: delete_database }
    action: deny
    priority: 100
    message: "Destructive action: deleting databases is never allowed"

  - name: escalate-transfer-funds
    condition: { field: tool_name, operator: eq, value: transfer_funds }
    action: deny
    priority: 90
    message: "Sensitive action: transfer_funds requires human approval"

  - name: allow-search-documents
    condition: { field: tool_name, operator: eq, value: search_documents }
    action: allow
    priority: 80

defaults:
  action: allow
```

| Operator | Semantic (port of MS's `_match_condition`) |
|---|---|
| `eq` / `ne` | Loose equality (numeric cross-type aware) |
| `gt` / `lt` / `gte` / `lte` | Comparable-based ordering |
| `in` | Value appears in target list |
| `contains` | Substring (strings) or membership (collections) |
| `matches` | Regex via `Pattern.matcher().find()` |

| Action | Decision mapping |
|---|---|
| `allow` | `PolicyDecision.admit()` |
| `deny` / `block` | `PolicyDecision.deny(message)` |
| `audit` | `PolicyDecision.admit()` + structured INFO log |

**Context map bridge** — rule `field:` references map to:

| Context key | `AiRequest` source |
|---|---|
| `message`, `system_prompt`, `model` | direct fields |
| `user_id`, `session_id`, `agent_id`, `conversation_id` | direct fields |
| `phase` | `pre_admission` \| `post_response` |
| `response` | accumulated response text (post-response only) |
| *anything else* | `AiRequest.metadata()` entries by exact key |

Two schemas are mutually exclusive per document — a YAML file carrying both `rules:` and `policies:` raises `IOException` at parse time.

### Conformance

`MsAgentOsYamlConformanceTest` copies example YAMLs byte-for-byte from `microsoft/agent-governance-toolkit@April-2026` (`docs/tutorials/policy-as-code/examples/`). If MS changes their upstream schema and our fixtures go stale, the test fails — parity is pinned in CI, not in marketing copy.

---

## Wiring in a Spring Boot app

```java
@Configuration
public class PoliciesConfig {
    private static final String POLICY_FILE = "atmosphere-policies.yaml";

    @Bean
    Object atmospherePolicyPlaneLoader(AtmosphereFramework framework) throws IOException {
        var resource = new ClassPathResource(POLICY_FILE);
        if (!resource.exists()) return List.of();
        try (var in = resource.getInputStream()) {
            var policies = new YamlPolicyParser().parse(
                    "classpath:" + POLICY_FILE, in);
            framework.getAtmosphereConfig().properties()
                    .put(GovernancePolicy.POLICIES_PROPERTY, policies);
            return policies;
        }
    }
}
```

`AtmosphereAiAutoConfiguration` also bridges Spring-managed `GovernancePolicy` beans onto `POLICIES_PROPERTY` automatically — so custom-coded policies work by dropping an `@Component` bean into the context.

For non-Spring deployments, register a `GovernancePolicy` `ServiceLoader` entry at `META-INF/services/org.atmosphere.ai.governance.GovernancePolicy`.

---

## Admin HTTP surface

All three endpoints are exposed by `AtmosphereAdminEndpoint` (Spring Boot) once `atmosphere-admin` is on the classpath. Wire-compatible with Microsoft Agent Governance Toolkit's `PolicyProviderHandler` ASGI app.

### `GET /api/admin/governance/policies`

```json
[
  {
    "name": "customer-pii-guard",
    "source": "classpath:atmosphere-policies.yaml",
    "version": "1.0",
    "className": "org.atmosphere.ai.governance.GuardrailAsPolicy"
  }
]
```

Reports runtime-confirmed state only (Correctness Invariant #5, Runtime Truth) — the list reflects what `AiEndpointProcessor` will actually apply on a turn, not what the YAML file or Spring beans might intend.

### `GET /api/admin/governance/summary`

```json
{ "policyCount": 2, "sources": ["classpath:atmosphere-policies.yaml"] }
```

### `POST /api/admin/governance/check`

Wire-compatible with MS's `POST /check`. Payload:

```json
{ "agent_id": "agent-a", "action": "call_tool", "context": { "tool_name": "delete_database" } }
```

Response:

```json
{
  "allowed": false,
  "decision": "deny",
  "reason": "Destructive action: deleting databases is never allowed",
  "matched_policy": "production-policy",
  "matched_source": "classpath:atmosphere-policies.yaml",
  "evaluation_ms": 3.27
}
```

External gateways (Envoy, Kong, Azure APIM) that already speak to MS's ASGI policy provider can point at this endpoint to use Atmosphere as the decision service without code changes.

### `GET /api/admin/governance/health`

Operator snapshot: kill-switch state, dry-run counters, SLO status, and
per-policy hash fingerprints for supply-chain drift detection.

```json
{
  "generatedAt": "2026-04-23T20:45:00Z",
  "killSwitch": { "armed": false },
  "policies": [
    { "name": "scope.support", "source": "yaml:...", "version": "1",
      "digest": "sha256:458be9dba..." }
  ],
  "dryRuns": [], "slos": []
}
```

### `GET /api/admin/governance/agt-verify`

Compliance export shaped for Microsoft's `agt verify` CLI — 25 findings
spanning OWASP Agentic Top 10 + EU AI Act / HIPAA / SOC2. External
procurement tooling that already consumes MS's compliance package format
can round-trip this output.

```json
{
  "schemaVersion": "agt-verify/1",
  "findings": [
    { "framework": "OWASP_AGENTIC_TOP_10", "controlId": "A01",
      "title": "Goal Hijacking", "status": "COVERED",
      "evidence": [
        { "class": "org.atmosphere.ai.annotation.AgentScope",
          "test":  "org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrailTest",
          "consumerGrep": "@AgentScope" }
      ]
    }
  ],
  "summary": { "OWASP_AGENTIC_TOP_10": { "COVERED": 9, "NOT_ADDRESSED": 1 } }
}
```

The `EvidenceConsumerGrepPinTest` CI gate walks `modules/**/src/main` +
`samples/**/src/main` and asserts every non-blank `consumerGrep` pattern
finds a production caller — claimed coverage can't drift.

### `POST /api/admin/governance/kill-switch/arm`

Break-glass — halts every admission decision without a redeploy.

```bash
curl -X POST http://localhost:8080/api/admin/governance/kill-switch/arm \
     -H 'Content-Type: application/json' \
     -d '{"reason":"incident-42","operator":"oncall"}'
```

Response stamps `{armed: true, reason, operator, armedAt}`. Disarm with
`POST /kill-switch/disarm` to restore traffic. Verified live on
spring-boot-multi-agent-startup-team: the same prompt that admitted at
0.11ms denies at 0.09ms while armed.

### `POST /api/admin/governance/reload`

Hot-reload a policy wrapped in `SwappablePolicy`. Request body carries
`{swapName, yaml}`; response carries the outgoing + incoming delegate
identity so the admin trail can log the swap.

---

## Multi-agent governance

Single-endpoint admission is only half the story — cross-agent dispatches
need the same enforcement. `FleetInterceptor` (module `atmosphere-coordinator`)
gates every outbound `AgentCall` before it leaves the coordinator.

### `FleetInterceptor` SPI

```java
@FunctionalInterface
public interface FleetInterceptor {
    Decision before(AgentCall call);
    sealed interface Decision {
        record Proceed() implements Decision {}
        record Rewrite(AgentCall modifiedCall) implements Decision {}
        record Deny(String reason) implements Decision {}
    }
}
```

Install via `AgentFleet.withInterceptor(interceptor)`. Denies synthesize
a failed `AgentResult` without consuming the transport hop; rewrites
forward modified args; proceed admits unchanged.

### `GovernanceFleetInterceptor`

Bridge from `FleetInterceptor` to a `GovernancePolicy` chain. Every outbound
`AgentCall` is synthesized into an `AiRequest(skill + args)` and evaluated
against the configured policies. Dispatch-edge metadata
(`fleet.dispatch.agent`, `fleet.dispatch.skill`) is stamped so policies
can inspect the dispatch target.

```java
@Prompt
public void onPrompt(String msg, AgentFleet fleet, StreamingSession s) {
    var governed = fleet.withInterceptor(new GovernanceFleetInterceptor(policies));
    var research = governed.agent("research").call("web_search", args);
    // a coordinator mistakenly dispatching "write Python" to research
    // gets denied at the fleet boundary — not just at the user entry
}
```

### Commitment records on cross-agent dispatch

Every dispatch emits a W3C Verifiable-Credential-subtype `CommitmentRecord`
when both (a) an `Ed25519CommitmentSigner` is installed on the fleet via
`JournalingAgentFleet.signer(signer)`, and (b) `CommitmentRecordsFlag`
is enabled (flag-off default; flip with the system
property `atmosphere.ai.governance.commitment-records.enabled=true` or
`CommitmentRecordsFlag.override(Boolean.TRUE)`).

```java
// In your Spring @Configuration:
@Bean CommitmentSigner commitmentSigner() {
    return Ed25519CommitmentSigner.generate();
}
@PostConstruct void enable() {
    CommitmentRecordsFlag.override(Boolean.TRUE);
}

// In your coordinator's @Prompt:
if (signer != null && fleet instanceof JournalingAgentFleet journaling) {
    journaling.signer(signer).principal("user:" + resource.uuid());
}
```

Records surface in the admin **Commitments** tab with Ed25519 verification
status. This is the unique combination: streaming transport + durable
checkpoints + cryptographic audit trail that survives pause/resume.

---

## Which samples demonstrate which goals

| Sample | MS YAML | Scope | Commitments | OWASP | E2E tests |
|---|:-:|:-:|:-:|:-:|:-:|
| [spring-boot-ms-governance-chat](../samples/spring-boot-ms-governance-chat/) | ✅ | ✅ | — | ✅ | — |
| [spring-boot-ai-classroom](../samples/spring-boot-ai-classroom/) | ✅ | ✅ | — | — | 8 |
| [spring-boot-multi-agent-startup-team](../samples/spring-boot-multi-agent-startup-team/) | ✅ | ✅ | ✅ | ✅ | 10 |
| [spring-boot-checkpoint-agent](../samples/spring-boot-checkpoint-agent/) | — | — | ✅ | — | 3 |
| [spring-boot-mcp-server](../samples/spring-boot-mcp-server/) | — | ✅ | — | ✅ | 7 |

Each sample boots the real Spring Boot context in its e2e tests and
asserts goal flows fire at runtime — no mocking at the governance seam.
See `StartupTeamGovernanceE2ETest`, `ClassroomGovernanceE2ETest`,
`CheckpointGovernanceE2ETest`, `McpGovernanceE2ETest`.

---

## Correctness invariants honored

| Invariant (see `.claude/CLAUDE.md`) | How it's honored |
|---|---|
| **#2 Terminal-path completeness** | Policy exceptions are fail-closed — any throw inside `evaluate()` becomes `Deny` |
| **#5 Runtime truth** | `GovernanceController` reports `POLICIES_PROPERTY` contents; not what was requested, not what's on the classpath |
| **#7 Mode parity** | `PolicyPlaneSourceParityTest` asserts YAML / programmatic / ServiceLoader sources yield identical admission decisions across Spring Boot + bare-JVM + Quarkus (SPI-level) |

Spring vs Quarkus parity at the framework level: `AiEndpointProcessor.instantiatePolicies()` uses ServiceLoader + `POLICIES_PROPERTY` — both deployments hit the same merge path. The Spring auto-config just adds a bean-to-property bridge; Quarkus users put policies directly in framework properties via a build step or the same ServiceLoader path.

---

## Related

- **Sample**: [`samples/spring-boot-ms-governance-chat/`](../samples/spring-boot-ms-governance-chat/) — ships a verbatim MS YAML policy and demonstrates every operator + action in a live chat gated by the built-in Atmosphere AI console.
- **Reference**: [atmosphere.github.io `reference/governance.md`](https://atmosphere.github.io/docs/reference/governance/) — full API reference.
- **Tutorial**: [atmosphere.github.io `tutorial/30-governance-policy-plane.md`](https://atmosphere.github.io/docs/tutorial/30-governance-policy-plane/) — walk-through from empty project to MS-YAML-enforced chat.
- **Module README**: [`modules/ai/README.md`](../modules/ai/README.md#governance-policy-plane-phase-a) — quick-start snippet + operator table.
- **Microsoft Agent Governance Toolkit**: [github.com/microsoft/agent-governance-toolkit](https://github.com/microsoft/agent-governance-toolkit) — the upstream toolkit whose YAML schema Atmosphere consumes verbatim.
