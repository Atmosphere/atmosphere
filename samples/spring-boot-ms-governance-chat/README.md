# MS Agent Governance Toolkit × Atmosphere — Chat

**The claim:** Atmosphere consumes [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) YAML policy artifacts verbatim. No translation, no conversion, no "mostly compatible" — the MS-schema YAML file that ships inside this sample could be dropped straight onto MS's own `PolicyEvaluator` and behave the same way.

**The proof:** `src/main/resources/atmosphere-policies.yaml` is authored in MS's own schema (`rules:` / `condition: {field, operator, value}` / `action: deny` / priority-sorted first-match). Atmosphere's `YamlPolicyParser` auto-detects the shape and produces a `MsAgentOsPolicy` that preserves MS's evaluation semantic. Conformance is pinned in `modules/ai/src/test/java/org/atmosphere/ai/governance/MsAgentOsYamlConformanceTest.java` against MS's own example YAMLs (copied unmodified from `microsoft/agent-governance-toolkit@April-2026`).

## Run

```bash
./mvnw spring-boot:run -pl samples/spring-boot-ms-governance-chat
open http://localhost:8090
```

## Try each rule

The bundled policy enforces three rules from three different MS operators.

| Rule | Operator | Try | Expected |
|---|---|---|---|
| `block-destructive-sql` | `matches` | `please DROP TABLE users` | denied — MS regex operator fires |
| `block-legal-escalation` | `contains` | `I'm going to sue us` | denied — MS substring operator fires |
| `block-ssn-shape` | `matches` | `my SSN is 123-45-6789` | denied — MS regex on digit pattern |
| (default: allow) | — | `hello` | admitted through the chain |

## Endpoints

- `GET /api/admin/governance/policies` — lists the live policy chain
- `GET /api/admin/governance/summary` — count + source URIs
- `POST /api/admin/governance/check` — MS `/check`-compatible decision endpoint; accepts `{agent_id, action, context}` and returns `{allowed, decision, reason, matched_policy, matched_source, evaluation_ms}`

```bash
curl -s -X POST http://localhost:8090/api/admin/governance/check \
  -H 'content-type: application/json' \
  -d '{"agent_id":"probe","action":"call_tool","context":{"message":"DROP TABLE logs"}}'
```

Expected response:

```json
{
  "allowed": false,
  "decision": "deny",
  "reason": "Destructive SQL statements are not permitted in this chat.",
  "matched_policy": "ms-governance-demo",
  "matched_source": "classpath:atmosphere-policies.yaml",
  "evaluation_ms": 1.23
}
```

## Where the claim is verified

- `modules/ai/src/main/java/org/atmosphere/ai/governance/MsAgentOsPolicy.java` — faithful port of MS's `_match_condition` (9 operators, 4 actions) + priority-sorted first-match evaluator.
- `modules/ai/src/test/resources/ms-agent-os/` — copies of MS's example YAMLs; conformance tests fail on upstream schema drift.
- `modules/admin/src/main/java/org/atmosphere/admin/ai/GovernanceController.java` — the `/check` endpoint's decision flow maps MS context fields onto `AiRequest` metadata and runs the real policy chain.

This sample is the user-visible artifact that closes the loop: edit the YAML (MS format), restart, governance posture changes — zero code edits, same artifact MS deployments consume.
