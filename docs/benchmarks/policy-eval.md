# Governance Policy-Plane Microbenchmark

Source: `modules/benchmarks/src/main/java/org/atmosphere/benchmarks/jmh/PolicyEvalBenchmark.java`

Six JMH benchmarks covering the governance hot path:

| Scenario | What it measures |
|---|---|
| `ruleBasedScopeAdmit` | `RuleBasedScopeGuardrail` on an in-scope query (admit path) |
| `ruleBasedScopeDeny` | `RuleBasedScopeGuardrail` on the canonical "write Python code" off-topic query (deny path) |
| `semanticIntentScopeAdmit` | `SemanticIntentScopeGuardrail` with a static-vector `EmbeddingRuntime` (measures cosine + threshold cost, not network RTT) |
| `msAgentOsRuleMatch` | `MsAgentOsPolicy.evaluate` against a 9-rule priority-sorted MS-schema chain |
| `ruleBasedInjectionSafe` | `RuleBasedInjectionClassifier` on a benign retrieval document |
| `ruleBasedInjectionFlagged` | `RuleBasedInjectionClassifier` on a "ignore previous instructions" prompt-injection document |

Each scenario reports **average time per evaluation** in microseconds.

## Status — measurements not yet published

**No numbers are published from this harness yet.** The benchmark's own
Javadoc says it plainly:

> Before publishing any number from this benchmark, pair with peer
> review — JMH results are easy to misinterpret (class loading, JIT
> transitions, allocation pressure).

Publishing a number that turns out wrong costs more credibility than
not publishing one. Specifically, the Atmosphere governance plane is
positioned alongside Microsoft Agent Governance Toolkit's own marketing
numbers ("<0.1 ms p99 enforcement", "31,000+ ops/sec on 100-rule
policies"), and our Verification Discipline (`feedback_no_fabricated_stats.md`)
forbids citing competitor numbers without our own measurement. Trading
one set of unverified numbers for another set of unverified numbers
(different reason) is the failure mode.

**Required before publication:**

1. Run the harness on a **production-representative box** — Linux x86_64,
   8+ pinned cores, no thermal throttling, kernel scheduler isolation
   (`isolcpus`), ASLR / address-space randomization controlled. A
   developer laptop is not representative.
2. Run with the documented JMH baseline methodology — five-minute
   warmup, ten-minute measurement, three forks, per-JVM `-Xmx2g`.
3. **External methodology review** of the run setup and harness
   interpretation — JMH allocation pressure, escape-analysis effects
   on `Blackhole.consume`, thermal stability of the chosen box.
4. Any comparison statement against MS Agent Governance Toolkit must
   measure both stacks on the **same hardware** with the **same
   methodology**. "Atmosphere is faster than MS" with one of those held
   constant is a marketing artifact, not a measurement.

When those conditions are met, replace this file's *Results* section
below with the published numbers.

## Reproducing the run

```bash
# Build the JMH uberjar (modules/benchmarks/target/benchmarks.jar
# contains the JMH harness plus every Atmosphere class needed).
./mvnw install -pl modules/benchmarks -am -DskipTests

# Run the policy-eval suite. Default JMH config matches the
# PolicyEvalBenchmark @Warmup / @Measurement annotations:
#   5 warmup iterations × 1s, 10 measurement iterations × 1s, 1 fork.
java -jar modules/benchmarks/target/benchmarks.jar \
     PolicyEvalBenchmark \
     -rf json -rff policy-eval-$(uname -m).json
```

For a peer-review-quality run, override the harness defaults to the
five-minute warmup / ten-minute measurement / three-fork baseline:

```bash
java -jar modules/benchmarks/target/benchmarks.jar \
     PolicyEvalBenchmark \
     -wi 5 -w 60s \
     -i 10 -r 60s \
     -f 3 \
     -rf json -rff policy-eval-$(uname -m)-baseline.json
```

The JMH JSON output is what an external reviewer reads — not a
human-edited summary.

## Results

*Awaiting peer-reviewed run. See "Status" above.*

When numbers land, this section should follow the format:

```
Hardware: <CPU>, <cores pinned>, <RAM>, <kernel>, <JVM>
Methodology: 5 × 60s warmup, 10 × 60s measurement, 3 forks, -Xmx2g
Reviewer: <name + date>
Raw JSON: <link to committed result file>

Scenario                          Mode  Cnt   Score   Error  Units
ruleBasedScopeAdmit               avgt  …     …       ±  …   us/op
ruleBasedScopeDeny                avgt  …     …       ±  …   us/op
…
```

## Related

- [Governance policy plane reference](../governance-policy-plane.md)
- `BusinessMdcBenchmark` — sister harness pinning the per-turn MDC cost
  on `AiEndpointHandler.invokePrompt`
