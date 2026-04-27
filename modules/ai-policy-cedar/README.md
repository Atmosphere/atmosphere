# atmosphere-ai-policy-cedar

Cedar (AWS) `PolicyParser` for the Atmosphere
[governance policy plane](../../docs/governance-policy-plane.md).
Operators who already author Cedar for Verified Permissions / Verified
Access can reuse the same dialect — and the same policies — to gate AI
dispatch on Atmosphere.

## Install

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai-policy-cedar</artifactId>
    <version>4.0.40</version>
</dependency>
```

The module also requires either the `cedar` CLI on `PATH` for the
default evaluator, or a JVM-native authorizer
(`com.cedarpolicy:cedar-java`) plugged in via the SPI — see *Authorizers*
below.

## Usage

```java
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.policy.cedar.CedarCliAuthorizer;
import org.atmosphere.ai.policy.cedar.CedarPolicy;

var cedar = """
    permit (
        principal in Group::"support-agents",
        action == Action::"invoke",
        resource is Agent
    )
    when { context.business_tenant_id != "blocked-tenant" };

    forbid (
        principal,
        action == Action::"invoke",
        resource == Agent::"finance-agent"
    )
    unless { principal in Group::"finance-cleared" };
    """;

GovernancePolicy policy = new CedarPolicy(
    "support-agents-allow",
    "cedar:support-allow.cedar",
    "1.0.0",
    cedar,
    new CedarCliAuthorizer());
```

## Request → Cedar mapping

| Atmosphere field | Cedar role |
|---|---|
| `userId` | `principal = User::"<userId>"` |
| (fixed) | `action = Action::"invoke"` |
| `agentId` | `resource = Agent::"<agentId>"` |
| `metadata` | `context` record |

Operators with different entity schemas subclass `CedarPolicy` and
override `buildContext`, `principalOf`, `resourceOf`.

Decision contract:

| Cedar verdict | `PolicyDecision` |
|---|---|
| `Allow` | `Admit` |
| `Deny` | `Deny("cedar denied: <reason>")` |
| Authorizer error / parse error | `Deny("policy evaluation failed: …")` (fail-closed) |

## Authorizers

`CedarAuthorizer` is an SPI. Two impls are common.

### `CedarCliAuthorizer` (default)

Shells out to `cedar authorize`. No JVM runtime dependency; operators
install the Cedar CLI the way they would for any other policy-as-code
tool (`brew install cedar-policy/tap/cedar`,
`cargo install cedar-policy-cli`, container image, etc.). The reference
distribution ships `cedar-cli`, so this is the path most operations
teams take first.

```java
new CedarCliAuthorizer();                    // cedar on PATH
new CedarCliAuthorizer("/usr/local/bin/cedar"); // explicit binary
```

### `cedar-java` JVM authorizer

For latency-sensitive paths where subprocess fork-exec is too expensive,
add the JVM binding and plug it in:

```xml
<dependency>
    <groupId>com.cedarpolicy</groupId>
    <artifactId>cedar-java</artifactId>
</dependency>
```

```java
public final class CedarJvmAuthorizer implements CedarAuthorizer { … }

new CedarPolicy(name, source, version, cedarSource,
        new CedarJvmAuthorizer());
```

## Operational notes

- **Boundary safety:** principal / resource / context are JSON-encoded
  and passed to the CLI via stdin — no shell interpolation of user
  values.
- **Fail-closed:** authorizer timeout, non-zero exit, or parse error
  denies the turn (Correctness Invariant #2).
- **Subprocess cost:** ~30–100 ms per evaluation with the CLI;
  `cedar-java` brings this under 1 ms once warm. Pick the authorizer
  that matches your latency budget.

## See also

- [Governance policy plane reference](../../docs/governance-policy-plane.md)
- Sister parser: `atmosphere-ai-policy-rego` (Rego / OPA dialect)
- Built-in YAML parser ships with `atmosphere-ai`
