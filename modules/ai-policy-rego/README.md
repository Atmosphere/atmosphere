# atmosphere-ai-policy-rego

Rego (Open Policy Agent) `PolicyParser` for the Atmosphere
[governance policy plane](../../docs/governance-policy-plane.md).
Operators who already write Rego for Kubernetes / Envoy / Conftest can
reuse the same dialect to gate AI dispatch on Atmosphere.

## Install

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai-policy-rego</artifactId>
    <version>4.0.40</version>
</dependency>
```

The module also requires the OPA binary on `PATH` for the default
evaluator (see *Evaluators* below).

## Usage

```java
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.policy.rego.OpaSubprocessEvaluator;
import org.atmosphere.ai.policy.rego.RegoPolicy;

var rego = """
    package atmosphere.governance

    default allow = false
    default reason = "default deny"

    allow {
        input.agent_id == "billing-agent"
        input.message != ""
    }

    reason = "missing tenant id" {
        not input["business.tenant.id"]
    }
    """;

GovernancePolicy policy = new RegoPolicy(
    "billing-agent-allow",
    "rego:billing-allow.rego",
    "1.0.0",
    rego,
    "data.atmosphere.governance",
    new OpaSubprocessEvaluator());

// Install via Spring bean / framework-property bridge / ServiceLoader,
// same as any other GovernancePolicy.
```

## Request → Rego `input` mapping

The Atmosphere `AiRequest` is flattened into the Rego `input` document:

| Atmosphere field | Rego key |
|---|---|
| `agentId` | `input.agent_id` |
| `userId`  | `input.user_id` |
| `sessionId` | `input.session_id` |
| `model` | `input.model` |
| `message` | `input.message` |
| `metadata.<key>` | `input.<key>` (top-level) |

Decision contract:

| Rego rule | `PolicyDecision` |
|---|---|
| `allow = true` | `Admit` |
| `allow = false` (no reason) | `Deny("rego policy denied")` |
| `allow = false` + `reason = "X"` | `Deny("X")` |
| evaluator error / parse error | `Deny("policy evaluation failed: …")` (fail-closed) |

## Evaluators

`RegoEvaluator` is an SPI — pick the impl that matches your operations
posture.

### `OpaSubprocessEvaluator` (default)

Shells out to the standard `opa eval` command. No JVM Rego runtime
dependency; operators install the OPA binary the same way they would
for Kubernetes admission webhooks (`brew install opa`,
`go install github.com/open-policy-agent/opa@latest`, container image,
etc.).

```java
new RegoPolicy(name, source, version, regoSource, query,
        new OpaSubprocessEvaluator());           // opa on PATH
new OpaSubprocessEvaluator("/usr/local/bin/opa"); // explicit binary
```

### Pure-Java evaluator (operator-supplied)

Implement `RegoEvaluator` against a pure-Java Rego runtime
(e.g. `jregorus`) when subprocess dispatch is unacceptable — air-gapped
deployments, cold-start sensitive serverless, etc.

```java
public final class JregorusEvaluator implements RegoEvaluator { … }

new RegoPolicy(name, source, version, regoSource, query,
        new JregorusEvaluator());
```

## Operational notes

- **Boundary safety:** the input document is JSON-encoded and passed to
  `opa eval` via stdin — no shell interpolation of user values.
- **Fail-closed:** evaluator timeout or non-zero exit denies the turn
  with `policy evaluation failed: …` (Correctness Invariant #2).
- **Subprocess cost:** ~50–150 ms per evaluation depending on policy
  size. For high-QPS endpoints prefer a long-lived OPA server (out of
  scope here) or a pure-Java evaluator.

## See also

- [Governance policy plane reference](../../docs/governance-policy-plane.md)
- Sister parser: `atmosphere-ai-policy-cedar` (Cedar / AWS dialect)
- Built-in YAML parser ships with `atmosphere-ai`
