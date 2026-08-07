# Troubleshooting — traps that have actually bitten

Each entry is a real failure mode from a past sweep, not a hypothetical.

## Environment

**Port already answers.** `sweep-sample.sh start` refuses to boot. This is
deliberate: a green probe against someone else's process is a false pass
(Invariant #5 — runtime truth). Move to another port. **Never kill a process
the sweep did not start** — the 2026-07 sweep found an unrelated `node` holding
:8080 and remapped every 8080-sample instead of touching it.

**A JVM survives teardown.** `sweep-sample.sh stop` fails loudly if the port is
still answering after the kill. Find the owner and kill by PID:

```bash
lsof -nP -iTCP:<port> -sTCP:LISTEN -t | while read -r p; do kill "$p"; done
```

Never `pkill -f java` — it reaches processes outside the sweep.

**macOS has no `setsid`.** The launcher uses bash job control (`set -m`) to put
each sample in its own process group so teardown can signal the whole group.
Hand-rolled `nohup … &` without that leaves grandchildren behind for the
Maven-wrapper boots.

**Stale jars in `target/`.** Several samples accumulate one jar per version
(`…-4.0.62-SNAPSHOT.jar`, `…-4.0.63-SNAPSHOT.jar`, …). The launcher takes the
newest by reverse sort, which is right for SNAPSHOT sequences but wrong across a
version rollover. When in doubt:

```bash
ls -la samples/<sample>/target/*.jar
./mvnw clean package -pl samples/<sample> -DskipTests
```

**Stale `~/.m2` from a parallel session.** Another agent session installing
SNAPSHOTs into the shared repo can swap the framework jars under you mid-sweep.
Re-install the reactor immediately before the sweep, and treat a result that
contradicts the code as suspect until you have re-installed and re-run.

**Worktree Maven cache.** After switching worktrees, run
`./mvnw install -am -q -DskipTests` before packaging samples, or you link against
another branch's SNAPSHOT.

## Boot

**Quarkus deployment self-jar.** A single-pass `./mvnw install` with tests on
fails the Quarkus `*-deployment` module. Prime with
`./mvnw install -DskipTests` first.

**"Timeout in the fork" with 0 failures** during the prep build is resource
starvation, not a real failure. Re-run that module in isolation.

**The WAR sample is slow.** `chat` boots via `mvn jetty:run`; Maven resolves
before Jetty starts. Allow a longer `--timeout` and do not read the delay as a
hang.

**`grpc-chat` has `runnable=false`.** It has no runnable packaged jar and boots
via `./mvnw exec:java -pl samples/grpc-chat` (`-Dhttp.port=…`; the gRPC listener
defaults to 9090). Maven boot is expected for this sample, not a finding.

**`checkpoint-agent` leaves state behind.** The default SQLite store writes
`target/checkpoint.db`. Delete it between runs so a stale row cannot fake a
"checkpoint created" pass.

## LLM backend

**`LLM_MODE=local` does not reach LangChain4j under Quarkus.**
`quarkus-ai-chat` configures `quarkus.langchain4j.openai.*` directly, defaulting
to the Gemini base URL with api-key `dummy`. It needs explicit values:

```
--env LLM_BASE_URL=http://localhost:11434/v1 --env LLM_API_KEY=ollama --env LLM_MODEL=qwen2.5:3b
```

**`real-ollama` is not a valid mode.** It is a CI-harness alias. `AiConfig`
matches the literal `local`; anything else falls through to remote/Gemini and
the sample will quietly try to reach the internet.

**An inherited `LLM_BASE_URL` silently redirects a "local" run.** On the
2026-08-07 shakedown, `spring-boot-ai-chat` and `spring-boot-one-dep-agent` both
logged `mode=local, model=qwen2.5:3b,
endpoint=https://generativelanguage.googleapis.com/v1beta/openai/` and failed
with a Gemini 404 rendered as an error frame — despite `--env LLM_MODE=local`.

Cause: the operator's shell profile exported
`LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/`, and the
launcher passed the whole inherited environment through. **The framework was
correct** — an explicit base URL outranks the mode's default, which is the
documented precedence — but the sweep was testing the wrong provider.

Two lessons, both now enforced:

1. `sweep-sample.sh` scrubs `LLM_*` and the provider keys by default
   (`SWEEP_KEEP_ENV=1` to inherit). Booting by hand? Scrub them yourself.
2. **Grep `AI config:` out of every boot log before driving.** Expect
   `endpoint=http://localhost:11434/v1`. Treat a surprising endpoint as a
   harness problem first and a framework problem second — check `env | grep LLM_`
   before writing up a runtime-truth finding.

The generalisation: when a sample behaves as if configured differently than you
configured it, audit the environment you handed it before you audit the code.

**Slow first boot on macOS: the SQLite native library.** Samples that reach the
SQLite JDBC driver spend their startup inside `System.load` of an extracted
`libsqlitejdbc.dylib`, reached from
`AtmosphereAiAutoConfiguration$TapeInstaller.afterSingletonsInstantiated` →
`SqliteTapeStoreFactory.create` → `DriverManager.getConnection`. The dylib is
ad-hoc signed and carries a `com.apple.provenance` xattr, so macOS evaluates it
through Gatekeeper on first load.

Measured on 2026-08-07 with `spring-boot-ai-chat`: the **first** boot never
completed within 10 minutes and was killed; the **next** boot, with the dylib
already vetted and cached in `$TMPDIR`, reached Tomcat-up in **126 s** and the
sample then passed fully. So this is a cold-start cost, not a defect, and not a
hang to file against the framework.

Consequences for the sweep:

- The launcher's default `--timeout` is **300 s** for this reason. Do not lower
  it; a timeout under the slowest legitimate boot manufactures phantom failures.
- Samples that never touch SQLite (`quarkus-chat`,
  `embedded-jetty-websocket-chat`, `spring-boot-one-dep-agent`) boot in seconds,
  so a slow boot in one sample says nothing about the others.
- `jstack <pid>` names the blocking frame in one step — reach for it before
  theorising about a startup hang, and check whether a second attempt is fast
  before writing anything up.

**Small models fail tool calls.** `qwen2.5:3b` emits invalid tool-call arguments
(Ollama answers 400); `7b` has produced an empty final response that an
agent-graph could not route ("stuck in node"). Use
`qwen2.5:7b-instruct-q4_K_M` for tool-heavy samples, and before recording a
regression, prove the identical flow completes on a capable model. That is the
difference between a model limitation and a framework bug.

**Do not use embacle for tool-calling samples.** `embacle-server --provider
claude_code` applies the host CLI's own configuration to responses (contaminating
output) and its tool-call fidelity is inconsistent. Use it only to demonstrate
"a capable model completes this cleanly", then stop it.

**Do not fall back to a paid key.** The paid LLM lane is retired. Quota
starvation is what reduced nine samples to plumbing-only in the 2026-06 sweep.

## Browser

**Chrome PNA blocks `ws://localhost` from `about:blank`.** Private Network
Access refuses the connection from an opaque origin, so raw WebSocket driving
needs a served same-origin page. This is why the headless samples are driven
over their wire protocol instead.

**Probing a long-poll endpoint hangs.** A GET on a suspended Atmosphere endpoint
never returns. Probe `/atmosphere/console/` or `/` for readiness. The launcher
caps the probe with `--max-time` so a suspended path reads as not-ready rather
than hanging the sweep.

**A cached Console bundle.** After a frontend change, hard-reload or the browser
serves the previous build and your evidence describes the old UI.

**Console noise from the previous sample.** `list_console_messages` is
per-page; reusing a page mixes two samples' errors. One `new_page` per sample,
`close_page` after.

**Silent transport fallback.** The status pill appends ` · fallback` when the
primary transport failed and the client degraded. A WebTransport sample running
on WebSocket "works" and is still a finding — read `data-transport` and
`data-via-fallback`, not just the word "Connected".

**A missing Console tab.** Tabs are conditional on what the server exposes. An
absent Validation / Checkpoints / Tape / Interactions tab on a sample that
should have it is a wiring regression, not a cosmetic difference.

## Reporting

**A gate that walks its own artifact self-certifies.** Any check that greps
`src/main` to validate a matrix or manifest must exclude that file from the walk
and strip comments/imports first, or the artifact's own citations satisfy it.

**Sample counts come from the filesystem.** `ls samples/` minus
`shared-resources`, cross-checked against `cli/samples.json`. Never from memory,
never from a previous report.

**"Fixed" bullets need commit hashes.** If you cannot produce the hash, the fix
did not happen.
