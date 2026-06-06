# atmosphere-verifier-smt

SMT-backed proof checking for the Atmosphere **plan verifier**. This module
proves **numeric invariants over the symbolic arguments of an LLM-emitted tool
plan** — the class of safety property the structural verifiers cannot decide,
because the values flowing into tool calls are symbolic and unbounded at verify
time.

It ships two interchangeable solver backends behind one SPI:

| Backend | Class | Solver | Native lib? | License | Default? |
|---|---|---|---|---|---|
| **SMTInterpol** | `SmtInterpolChecker` (priority 100) | pure-JVM | **No** | LGPL-3.0 | ✅ zero-config |
| **Z3** | `Z3SmtChecker` (priority 200) | native | Yes (opt-in) | MIT | opt-in |

`SmtChecker.resolve()` automatically prefers Z3 when its native libraries are
present, and transparently falls back to SMTInterpol otherwise. Both backends
run the **identical proof logic** (`AbstractJavaSmtChecker`), so enabling Z3
changes only the solver engine, not the verified semantics.

---

## 1. Where this fits — the plan verifier

The verifier (`atmosphere-verifier`) checks an LLM-produced tool **plan**
(`Workflow` = an ordered list of `ToolCallNode`s) against a `Policy` **before any
tool executes**. It runs a chain of `PlanVerifier`s, cheapest first:

| Verifier | Proves | Decidable structurally? |
|---|---|---|
| `AllowlistVerifier` | every tool called is on `Policy.allowedTools()` | ✅ |
| `WellFormednessVerifier` | the plan AST is well-formed | ✅ |
| `CapabilityVerifier` | each tool's required capabilities are granted | ✅ |
| `TaintVerifier` | tainted data does not reach a sink against the taint rules | ✅ |
| `AutomatonVerifier` | the call sequence is accepted by the security automaton | ✅ |
| **`SmtVerifier`** | **numeric invariants hold for *all* runtime values** | ❌ needs a solver |

The first five are finite, structural decisions. The sixth — **this module** —
is where an SMT solver earns its keep: reasoning about relationships between
**unbounded symbolic values** that only exist at run time.

---

## 2. What the SMT layer proves

A `Policy` can declare `NumericInvariant`s alongside its allowlist and taint
rules. The canonical example is a money-transfer guard:

> Tool `transfer`, argument `amount`, must be `<= ref(balance)` — the running
> balance the plan read at run time.

`balance` is **symbolic**: its value is whatever the bank returns at run time
(and may be influenced by attacker-controlled data). The checker proves the
invariant for **every** possible runtime value by asserting its **negation** and
asking the solver whether that is satisfiable:

| Plan binds `amount` to | Negation asserted | Solver | Outcome |
|---|---|---|---|
| `SymRef("balance")` (the read value) | `balance > balance` | **UNSAT** | proven — no violation |
| `SymRef("userInput")` (unrelated symbol) | `userInput > balance` | **SAT** | counterexample — violation |
| literal `500` against `<= 1000` | `500 > 1000` | UNSAT | proven |
| literal `5000` against `<= 1000` | `5000 > 1000` | SAT | violation |

**Data-flow identity.** Both `SymRef` arguments and `RefBound`s are keyed by
their binding name (`sym$<name>`). The same name on the argument and on the
bound denotes the *same* solver variable — so passing the read value straight
through (`amount := SymRef("balance")`) is exactly what discharges the proof,
while passing an unrelated symbol leaves the relationship unconstrained and is
flagged with a concrete counterexample.

---

## 3. Declaring a numeric invariant

`NumericInvariant` lives in the base `atmosphere-verifier` module:

```java
import org.atmosphere.verifier.policy.NumericInvariant;
import org.atmosphere.verifier.policy.NumericInvariant.Op;          // LE, LT, GE, GT, EQ
import org.atmosphere.verifier.policy.NumericInvariant.RefBound;    // bound is a symbolic ref
import org.atmosphere.verifier.policy.NumericInvariant.LiteralBound;// bound is a constant

// transfer.amount <= ref(balance)
var refInvariant = new NumericInvariant("transfer", "amount", Op.LE, new RefBound("balance"));

// transfer.amount <= 1000
var capInvariant = new NumericInvariant("transfer", "amount", Op.LE, new LiteralBound(1000));

Policy policy = Policy.allowlist("payments", "transfer")
        .withNumericInvariants(List.of(refInvariant, capInvariant));
```

Semantics: when a matching tool call **omits** the constrained argument the
invariant is vacuously satisfied for that call; a **non-numeric** literal
argument is reported as a violation.

---

## 4. Backend selection

`SmtChecker.resolve()` loads every `SmtChecker` via `ServiceLoader` and selects
the **highest-priority** one whose `isAvailable()` returns `true`:

```
Z3SmtChecker (200)  ── available only if libz3/libz3java load ──┐
SmtInterpolChecker (100) ── always available (pure-JVM) ────────┤──▶ resolve()
NoOpSmtChecker (0, in atmosphere-verifier) ── always green ─────┘
```

`isAvailable()` reflects **confirmed runtime state** (Correctness Invariant #5):
it constructs one `SolverContext` and returns `true` only if the solver actually
loads — never on classpath presence alone. So `Z3SmtChecker` reports unavailable
(and `resolve()` falls back to SMTInterpol) whenever the Z3 natives are missing.

---

## 5. Enabling Z3 (opt-in)

Z3 is **MIT-licensed**, faster and more capable than SMTInterpol. It needs three
artifacts: the JNI **bindings** jar plus **two native libraries** (`libz3` =
the solver, `libz3java` = the JNI bridge) on `java.library.path`.

> ✅ **Verified on macOS arm64 (Apple Silicon)** with `java-smt 5.0.1` +
> `javasmt-solver-z3 4.14.0`. Z3 is **not** Apple-Silicon-incompatible — it
> simply requires the native classifiers below, which java-smt publishes for
> this platform.

### 5.1 Add the bindings + native artifacts

```xml
<!-- Z3 JNI bindings (the com.microsoft.z3 classes) -->
<dependency>
  <groupId>org.sosy-lab</groupId>
  <artifactId>javasmt-solver-z3</artifactId>
  <version>4.0.51</version>
</dependency>
<!-- Z3 solver native + JNI bridge native (pick YOUR platform's classifier/type) -->
<dependency>
  <groupId>org.sosy-lab</groupId>
  <artifactId>javasmt-solver-z3</artifactId>
  <version>4.0.51</version>
  <classifier>libz3-arm64</classifier>     <!-- macOS arm64 -->
  <type>dylib</type>
</dependency>
<dependency>
  <groupId>org.sosy-lab</groupId>
  <artifactId>javasmt-solver-z3</artifactId>
  <version>4.0.51</version>
  <classifier>libz3java-arm64</classifier>
  <type>dylib</type>
</dependency>
```

**Per-platform classifier / type:**

| Platform | classifier (libz3 / libz3java) | type |
|---|---|---|
| Linux x64 | `libz3-x64` / `libz3java-x64` | `so` |
| macOS x64 (Intel) | `libz3-x64` / `libz3java-x64` | `dylib` |
| **macOS arm64 (Apple Silicon)** | `libz3-arm64` / `libz3java-arm64` | `dylib` |
| Windows x64 | `libz3-x64` / `libz3java-x64` | `dll` |

### 5.2 Put the natives on `java.library.path`

java-smt loads the natives by the file names `libz3.<ext>` and
`libz3java.<ext>`. Copy the resolved classifier artifacts into one directory
under those names and point the JVM at it. A reproducible Maven wiring:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>copy-z3-natives</id>
      <phase>process-resources</phase>
      <goals><goal>copy</goal></goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>org.sosy-lab</groupId><artifactId>javasmt-solver-z3</artifactId>
            <version>4.0.51</version><classifier>libz3-arm64</classifier><type>dylib</type>
            <destFileName>libz3.dylib</destFileName>
          </artifactItem>
          <artifactItem>
            <groupId>org.sosy-lab</groupId><artifactId>javasmt-solver-z3</artifactId>
            <version>4.0.51</version><classifier>libz3java-arm64</classifier><type>dylib</type>
            <destFileName>libz3java.dylib</destFileName>
          </artifactItem>
        </artifactItems>
        <outputDirectory>${project.build.directory}/z3</outputDirectory>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Then run with `-Djava.library.path=target/z3` (or set it in your app launcher /
container). With the natives present, `Z3SmtChecker.isAvailable()` flips to
`true` and `SmtChecker.resolve()` selects Z3 automatically — no code change.

> **Quick manual check** (what this module's maintainers ran to verify arm64):
> resolve the two `*-arm64.dylib` classifier artifacts, copy them to `/tmp/z3libs`
> as `libz3.dylib` / `libz3java.dylib`, and run any test with
> `-Djava.library.path=/tmp/z3libs`. `Z3SmtChecker.isAvailable()` returns `true`
> and the `Z3SmtCheckerTest` native proofs run instead of skipping.

---

## 6. Licensing

| Component | License | Notes |
|---|---|---|
| `java-smt` (the unified API) | **Apache-2.0** | |
| **SMTInterpol** (default backend) | **LGPL-3.0** | Used as an *unmodified library dependency* — linked against the published artifact, not modified or re-distributed in modified form. Does **not** affect Atmosphere's Apache-2.0 license. |
| **Z3** (opt-in backend) | **MIT** | Permissive. Enable it (Section 5) if you prefer to avoid the LGPL dependency or want Z3's performance. |

If a fully permissive default is required, exclude SMTInterpol and ship Z3 (or
CVC5, BSD-3) as the sole backend — `AbstractJavaSmtChecker` is solver-agnostic,
so only the dependency set changes.

---

## 7. Scope & limitations (honest boundaries)

- **Theory:** linear integer arithmetic over tool-call arguments and symbolic
  bindings. Real/rational and bit-vector reasoning are not wired today.
- **Plan shape:** the verifier AST (`PlanNode`) is currently linear
  (`ToolCallNode` only); there is **no control-flow** (conditionals/loops) yet.
  The SMT layer therefore proves value relationships across a straight-line
  plan. When control-flow nodes are added to the AST, path-sensitive cost/budget
  proofs become the natural next invariant class — and Z3 (vs SMTInterpol) earns
  a larger margin there.
- **Nested SymRefs:** only top-level argument values are lowered; deeply nested
  `SymRef`s inside lists/maps are treated as non-numeric (mirrors the executor's
  top-level resolution).
- **Determinism:** each `check(...)` is a pure function of `(workflow, policy)`;
  a fresh `SolverContext` is created and closed per call.
