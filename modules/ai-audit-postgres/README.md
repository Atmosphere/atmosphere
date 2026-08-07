# atmosphere-ai-audit-postgres

`AuditSink` implementation that writes Atmosphere governance audit
records to a JDBC table. Pairs with the
[governance policy plane](../../docs/governance-policy-plane.md) — every
`GovernancePolicy.evaluate()` decision is persisted alongside the
in-memory ring buffer the admin console reads from.

Targets Postgres (`context_snapshot` column declared `JSONB`) but works
against any JSR-221 `DataSource`. Tests exercise H2 in-memory.

## Install

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai-audit-postgres</artifactId>
    <version>4.0.65</version>
</dependency>
```

## Usage

```java
import org.atmosphere.ai.audit.postgres.JdbcAuditSink;
import org.atmosphere.ai.governance.AsyncAuditSink;
import org.atmosphere.ai.governance.GovernanceDecisionLog;

DataSource ds = /* HikariCP, Spring Boot autoconfigured DataSource, … */;

// Default table name "governance_audit_log", auto-create DDL on startup.
var jdbc = new JdbcAuditSink(ds);

// Install a non-NOOP decision log FIRST, then attach the sink. Sinks fan out
// from record(), which is a no-op on the default NOOP log (capacity 0) — so
// addSink() on installed() silently drops every entry unless a real log is
// installed. The Spring Boot starter auto-installs one (capacity 500 by
// default, via atmosphere.ai.governance.decision-log.capacity); install
// explicitly for standalone / non-Spring use, then add the sink to it.
//
// Wrap with AsyncAuditSink so admission threads never stall on
// connection-pool exhaustion (Backpressure invariant).
GovernanceDecisionLog.install(2_000).addSink(new AsyncAuditSink(jdbc, 10_000));
```

## Schema

Auto-created on construction unless you pass `autoCreate=false`:

```sql
CREATE TABLE IF NOT EXISTS governance_audit_log (
    id               BIGSERIAL PRIMARY KEY,
    ts               TIMESTAMP WITH TIME ZONE NOT NULL,
    policy_name      VARCHAR(255) NOT NULL,
    policy_source    VARCHAR(512) NOT NULL,
    policy_version   VARCHAR(64)  NOT NULL,
    decision         VARCHAR(32)  NOT NULL,
    reason           TEXT         NOT NULL,
    evaluation_ms    DOUBLE PRECISION NOT NULL,
    context_snapshot JSONB        NOT NULL  -- CLOB on non-Postgres
);
CREATE INDEX IF NOT EXISTS idx_governance_audit_log_ts_policy
    ON governance_audit_log (ts DESC, policy_name);
```

Set `autoCreate=false` when DDL is owned by Flyway / Liquibase /
platform migrations:

```java
new JdbcAuditSink(ds, "audit_governance", false);
```

### Schema versioning

When `autoCreate` is set, the sink stamps the schema it creates through the
package-local `SchemaMigrations` (a copy of the canonical
`org.atmosphere.checkpoint.SchemaMigrations`, copied so this module gains no
dependency on `atmosphere-checkpoint`). The version lives in a shared
`atmosphere_schema_version (component, version)` table keyed by the **table
name**, so several sinks over different tables version independently.

With `autoCreate=false` the sink writes no DDL **and no version row** — it does
not stamp a schema an operator owns.

- Fresh table → the create step runs, version stamped.
- Unstamped table that already exists → adopted as version 1.
- Already current → no-op.
- Stamped **newer** than this build → construction fails with an
  `IllegalStateException` naming the database, the found version, and the
  supported version. Note the asymmetry with the failure isolation above: a
  *DDL* failure stays a warning ("assuming external DDL"), but a *version*
  refusal propagates — rows would otherwise be written against a schema this
  code cannot read.

The audit-log schema is at **version 1**. To add a migration, append one
idempotent step to `createSchemaIfMissing()` — never edit or reorder an
existing step — and update `JdbcAuditSinkSchemaVersionTest`. The full
convention is documented in `modules/checkpoint/README.md`.

## Operational notes

- **DataSource ownership:** the sink never closes the `DataSource` —
  caller retains ownership (matches Spring `@Bean` lifecycle).
- **Table name validation:** rejected unless it matches
  `[A-Za-z_][A-Za-z0-9_]*` — string concatenation into the SQL is the
  reason; this validator is the boundary check.
- **Shutdown:** call `close()` (or `AsyncAuditSink.close()` if wrapped)
  before JVM exit to drain in-flight inserts.
- **Failure isolation:** insert failures never propagate to the policy
  pipeline — connection blips yield warn-level logs and the in-memory
  ring buffer stays authoritative.

## Postgres JSONB query examples

```sql
-- Denials in the last hour for tool 'drop_database'
SELECT ts, policy_name, reason
FROM governance_audit_log
WHERE decision = 'deny'
  AND context_snapshot->>'tool_name' = 'drop_database'
  AND ts > now() - interval '1 hour'
ORDER BY ts DESC;

-- Top denying policies by tenant
SELECT context_snapshot->>'business.tenant.id' AS tenant,
       policy_name,
       COUNT(*) AS denials
FROM governance_audit_log
WHERE decision = 'deny'
GROUP BY 1, 2
ORDER BY denials DESC
LIMIT 20;
```

## See also

- [Governance policy plane reference](../../docs/governance-policy-plane.md)
- Sister sink: `atmosphere-ai-audit-kafka` (Kafka topic writer)
