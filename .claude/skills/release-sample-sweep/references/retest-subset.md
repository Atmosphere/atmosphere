# Re-test subset — blast radius after a fix

A PASS is evidence about **one artifact**. A fix produces a new artifact, so
every pass recorded against the old one is only still valid if the fix could not
reach that sample. This file maps "what the fix touched" to "which
already-passing samples must be re-driven".

Always re-run the failed sample's **full headline flow**, not just the step that
broke. Then add the subset below.

## Family membership (verified — re-derive if the repo moved)

```bash
# AI runtime per sample
for d in samples/*/; do s=$(basename "$d"); [ "$s" = shared-resources ] && continue; \
  echo "$s -> $(grep -o 'atmosphere-\(langchain4j\|spring-ai[a-z-]*\|koog\|cohere\|adk\|embabel\)' "$d/pom.xml" 2>/dev/null | sort -u | tr '\n' ',')"; done

# Samples that enable WebTransport
grep -rln 'web-transport\|webTransport' samples/*/src/main/resources/
```

**AI runtime**

| Runtime | Samples |
|---|---|
| Built-in | `ai-chat`, `ai-classroom`, `channels-chat`, `agui-chat`, `checkpoint-agent`, `coding-agent`, `guarded-email-agent`, `ms-governance-chat`, `mcp-server`, `a2a-agent`, `passivation-agent`, `reattach-harness`, `durable-sessions`, `otel-chat`, `one-dep-agent`, `admin-bundle` |
| LangChain4j | `ai-tools`, `dentist-agent`, `orchestration-demo`, `personal-assistant`, `quarkus-ai-chat` |
| Spring AI | `rag-chat`, `spring-ai-advisors` |
| Cohere | `browser-agent` |
| Multi-runtime | `multi-agent-startup-team` (adk + embabel + koog + langchain4j + spring-ai) |

**Transport**

| Transport | Samples |
|---|---|
| WebTransport enabled | `ai-chat`, `chat`, `ai-tools`, `ai-classroom`, `rag-chat`, `otel-chat`, `agui-chat`, `browser-agent`, `mcp-server`, `multi-agent-startup-team` |
| gRPC / Connect | `grpc-chat` |
| AG-UI SSE | `agui-chat` |
| A2A console transport | `a2a-agent` |
| Plain WebSocket | everything else |

**Container**

| Container | Samples |
|---|---|
| Spring Boot | the 26 `spring-boot-*` samples |
| Quarkus | `quarkus-chat`, `quarkus-ai-chat` |
| Embedded Jetty | `embedded-jetty-websocket-chat`, `kotlin-dsl-chat`, `grpc-chat` |
| Servlet WAR | `chat` |

## Blast-radius table

| Fix touched | Re-test subset | Why |
|---|---|---|
| One sample's own `src/` or `pom.xml` | **That sample only** | Nothing else links the artifact. The 2026-07 langchain4j fix was exactly this — samples already verified stayed byte-identical |
| `modules/cpr` (core runtime) | One sample **per container and per transport**: `spring-boot-chat`, `spring-boot-ai-chat` (WebTransport), `quarkus-chat`, `chat` (WAR), `embedded-jetty-websocket-chat`, `grpc-chat`, `kotlin-dsl-chat`, `spring-boot-agui-chat` (SSE) | Core sits under every transport and every container |
| Console bundle (`modules/spring-boot-starter/frontend/`) or `atmosphere.js` | One sample **per Console surface class**: `spring-boot-chat` (chat), `guarded-email-agent` (Validation), `checkpoint-agent` (Checkpoints), `coding-agent` (Interactions), `multi-agent-startup-team` (Tape + fleet), `admin-bundle` (admin), plus `rag-chat` / `agui-chat` / `grpc-chat` for the non-WS transports | The Console is the validation surface for ~26 samples; a bundle change invalidates all of their UI evidence |
| `modules/ai` | One sample **per AI runtime**: `ai-chat` (built-in), `dentist-agent` + `ai-tools` (LangChain4j), `rag-chat` (Spring AI), `browser-agent` (Cohere), `multi-agent-startup-team` (multi-runtime) | Dispatch, tool bridging, and streaming are shared; runtimes diverge |
| `modules/spring-boot-starter` (Java) | `ai-chat`, `spring-boot-chat`, `one-dep-agent`, `admin-bundle` | Auto-configuration reaches all 26 Spring Boot samples; these four cover AI, plain, single-dep, and bundle wiring |
| `modules/quarkus-extension`, `modules/quarkus-*` | `quarkus-chat`, `quarkus-ai-chat` | Only two Quarkus samples — re-run both, no subsetting needed |
| `modules/admin` / admin bundle | `admin-bundle`, `ms-governance-chat` (Policies/Decisions), `checkpoint-agent` (Checkpoints), `multi-agent-startup-team` (Tape) | Admin surfaces are tab-conditional; each tab is a separate wiring path |
| `modules/coordinator` | `checkpoint-agent`, `multi-agent-startup-team`, `personal-assistant`, `orchestration-demo` | The `@Coordinator` / `@Fleet` consumers |
| `modules/checkpoint*` | `checkpoint-agent`, `passivation-agent`, `durable-sessions`, `multi-agent-startup-team` | Durable state consumers |
| `atmosphere.js` (any export) | Every browser-driven sample (the Console bundles it) **and** the Expo client — the RN client links the local `dist/`, so it is the only check of the `./react-native` export | A client-library change reaches the browser and the native runtime by different paths |
| `atmosphere.js` `./react-native` only | The Expo client (Phase 1b) | No other surface consumes that export |
| `cli/atmosphere`, `cli/samples.json`, `cli/npx`, `cli/install.sh` | The full CLI pass (Phase 1c). Samples themselves are unaffected unless `samples.json` changed — then re-check `list`/`info` against `ls samples/` | The CLI is a separate artifact from the samples it launches |
| A sample renamed, added, or removed | That sample's own pass **plus** the CLI registry checks, `cmd_new` template map, and `release-gate-samples.sh` coverage map | Sample changes must land with `samples.json`, the CLI map, the READMEs, and CI in the same commit |
| Root `pom.xml`, `bom/`, a managed dependency version | **Full re-sweep** — all three surfaces | A managed-version change reaches every artifact. This is exactly the class that produced both historical sweep bugs |
| Build/CI scripts only, docs only | Nothing — but the CI lanes must be green | No artifact changed |

## Rules

1. **Re-drive, don't re-reason.** "The fix can't affect X" is a hypothesis. If X
   is in the subset, drive it.
2. **Rebuild before re-testing.** `./mvnw install -DskipTests -Pfastinstall` for
   the affected modules **and** the samples that bundle them, or you re-test the
   old jar and record a false pass.
3. **Fresh Console.** After a frontend change, hard-reload — a cached bundle
   shows you the old UI.
4. **State the skip.** The ledger's re-test section names every sample re-driven
   and every one deliberately not, with the reason. Silent subsetting reads as
   "everything was re-verified" and is the dishonesty this whole procedure
   exists to prevent.
5. **A second fix restarts this table.** Two fixes in different families mean
   the union of both subsets.
