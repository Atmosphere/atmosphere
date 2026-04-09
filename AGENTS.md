## Build System: Maven

**This project uses Apache Maven with the Maven Wrapper (`./mvnw`).**

### Commands
| Task | Command |
|------|---------|
| Full build | `./mvnw install` |
| Fast build (skip checks) | `./mvnw install -Pfastinstall` |
| Compile only | `./mvnw compile` |
| Run tests | `./mvnw test` |
| Run single test | `./mvnw test -pl modules/cpr -Dtest=BroadcasterTest` |
| Skip tests | `./mvnw install -DskipTests` |
| Checkstyle only | `./mvnw checkstyle:checkstyle` |
| PMD only | `./mvnw pmd:check` |

### Module Build
Build a specific module (faster iteration):
```bash
# Build only atmosphere-runtime (cpr)
./mvnw install -pl modules/cpr -DskipTests

# Build only spring-boot-starter
./mvnw install -pl modules/spring-boot-starter -DskipTests

# Build only quarkus extension
./mvnw install -pl modules/quarkus-extension -DskipTests
```

## Git Workflow

### Hooks Setup
**Run this at the START OF EVERY SESSION:**
```bash
git config core.hooksPath .githooks
```
This enables pre-commit, commit-msg, and pre-push hooks. Sessions get archived/revived, so this must run EVERY time you start working.

**NEVER use `--no-verify` when committing or pushing.** The hooks enforce:
- Apache 2.0 copyright headers on all Java source files
- No unused or duplicate imports in staged Java files
- Commit message format (max 2 lines, conventional commits recommended)
- No AI-generated commit signatures
- Pre-push: Maven build must pass (via validation marker)

### Commit Message Format
Use conventional commit prefixes. The commit-msg hook warns if missing:
```
type(scope): description
```
Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`, `build`, `revert`

Examples:
```
feat(cpr): add WebSocket reconnection support
fix(spring-boot): resolve auto-configuration ordering
chore: update Jetty to 12.0.16
```

Rules enforced by hooks:
- Maximum 2 non-empty lines (summary + optional detail)
- First line under 100 characters (50-72 recommended)
- No AI-generated signatures (`Co-Authored-By: Claude`, `Generated with`, etc.)
- **NEVER add `Co-authored-by: Copilot` or any AI co-author trailer** — the commit-msg hook will reject it
- Do not add ANY trailer lines (Co-authored-by, Signed-off-by, etc.) to commit messages

### Branch Strategy
- Main branch: `main` (development), `atmosphere-2.6.x` (legacy)
- Feature branches for new features
- Bug fixes go directly to `main`

## Project Structure

```
atmosphere/
├── config/                        (checkstyle, PMD rulesets)
├── modules/
│   ├── cpr/                       (atmosphere-runtime - core framework)
│   ├── spring-boot-starter/       (Spring Boot 4.0 integration)
│   └── quarkus-extension/         (Quarkus 3.21+ integration)
│       ├── runtime/
│       └── deployment/
├── samples/
│   ├── chat/                      (Jetty embedded sample)
│   ├── embedded-jetty-websocket-chat/
│   ├── spring-boot-chat/
│   └── quarkus-chat/
├── atmosphere.js/                 (TypeScript client library)
├── assembly/
└── pom.xml                        (4.0.2-SNAPSHOT, JDK 21)
```

### Key Artifacts
| Module | ArtifactId | Description |
|--------|-----------|-------------|
| Core | `atmosphere-runtime` | Main framework JAR |
| Spring Boot | `atmosphere-spring-boot-starter` | Spring Boot 4.0 auto-configuration |
| Quarkus Runtime | `atmosphere-quarkus-extension` | Quarkus 3.21+ runtime |
| Quarkus Deployment | `atmosphere-quarkus-extension-deployment` | Quarkus build-time processing |

## Writing Code

- Java version: **21** (configured in root pom.xml `<release>21</release>`)
- License: Apache 2.0
- All Java files MUST have the copyright header (enforced by pre-commit hook)
- Commit without AI assistant-related commit messages
- Do not add AI-generated commit text in commit messages
- **NEVER add `Co-authored-by:` trailers to commits** — no Copilot, no Claude, no AI attribution of any kind
- NEVER USE `--no-verify` WHEN COMMITTING CODE
- Match the style and formatting of surrounding code
- Make the smallest reasonable changes to get to the desired outcome
- NEVER remove code comments unless you can prove they are actively false

### Copyright Header (Required)
All Java source files must start with:
```java
/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
```

### Java Code Style
- Prefer composition over inheritance
- Use `try-with-resources` for all `AutoCloseable` resources
- Prefer `Optional` over null returns for public APIs
- Use `var` for local variables when the type is obvious from context
- Prefer `switch` expressions (JDK 21) over if/else chains for enum handling
- Use sealed classes/interfaces where appropriate
- Use records for immutable data carriers
- Use pattern matching (`instanceof` patterns, switch patterns) where it improves readability
- Avoid raw types - always parameterize generics
- Use `@Override` on all overriding methods
- Prefer `StringBuilder` over string concatenation in loops
- Use `List.of()`, `Map.of()`, `Set.of()` for unmodifiable collections
- All tests use JUnit 5 (`org.junit.jupiter`)

### Build Enforcement
- **Compiler**: `javac -Xlint:all,-processing,-serial` is enabled — the compiler flags unused imports, unchecked casts, deprecation usage, raw types, and other issues as warnings. **Zero compiler warnings are required.**
- **Checkstyle**: runs in `validate` phase (failsOnError=true). Enforces `UnusedImports` and `RedundantImport` rules — the build fails if unused or duplicate imports are present. Config in `config/atmosphere-checkstyle.xml`.
- **PMD**: runs in `validate` phase (failsOnError=true). Config in `config/atmosphere-pmd-ruleset.xml`.
- **Pre-commit hook**: blocks commits containing unused or duplicate imports in staged Java files
- All checks can be skipped with `-Pfastinstall` for local iteration, but **you MUST run a full `./mvnw compile` (without `-Pfastinstall`) before committing** to verify zero warnings.
- **Do NOT introduce new `@SuppressWarnings` annotations** without justification. If a suppression is necessary (e.g., unavoidable raw type from a third-party API), add a comment explaining why.

## Correctness Invariants (Blocking)

Violations of these rules are **release-blocking** regardless of feature scope. These invariants exist because ~45 bugs (2 P0, 15 P1) were traced to their absence during a 2-week review.

### 1. Ownership: Only the creator of a resource may release/close/shutdown it
- **Do not destroy, release, or shut down resources you did not create.** Track ownership explicitly.
- When accepting external resources (executors, pools, connections), wrap them to prevent lifecycle operations.
- Every `start()` must have a symmetric `stop()` that undoes all registrations.
- Registration actions (listeners/handlers/hooks) MUST have explicit uninstall/removal on stop.
- Netty/direct buffers MUST be released on all terminal/error/removal paths.

### 2. Terminal Path Completeness: Every exit path must complete/release/reset
- **Every terminal path (success, failure, cancel, timeout, reject) must leave the system in a completed state.**
- Futures must be resolved. Flags must be reset. Buffers must be released.
- Close must be idempotent (CAS or close-once pattern).
- If a guard checks a counter, the counter must actually be incremented somewhere.

### 3. Backpressure: Never ignore rejection or capacity signals
- **Never ignore backpressure or rejection signals.** Check return values of `offer()`, handle `RejectedExecutionException`.
- Every cache and buffer must declare a size bound or eviction policy.
- Unbounded data structures fed by external input are a DoS vector.

### 4. Boundary Safety: Validate/encode/frame at every boundary
- **At every boundary (wire, filesystem, shell, URL, HTTP), validate and encode before interpretation.**
- Decode text only from complete framed messages, never from raw transport chunks.
- Do not mutate binary payloads (no appending delimiters to byte arrays).
- Normalize and validate all filesystem paths (`resolve().normalize()` + `startsWith()` check).
- Use shell arrays for argument lists; never expand unquoted variables.
- URL-encode all dynamic query/path components.
- Content-type matching must be case-insensitive.
- Return 400 (not 500) for malformed user input; catch parse exceptions at the boundary.

### 5. Runtime Truth: Advertise only confirmed runtime state
- **Advertise and report only confirmed runtime state, never configuration intent or classpath presence.**
- Capability flags must reflect actual running state.
- Info/discovery endpoints must return runtime-resolved values.
- Feature advertisement (Alt-Svc, transport support) must be suppressed when startup fails.
- Classpath detection must be gated on runtime evidence, not just `Class.forName()` success.

### 6. Security: Every mutating surface requires explicit authorization
- **Every mutating/admin endpoint requires explicit authentication and authorization.** Default deny.
- Security/integrity verification MUST fail closed by default; any fail-open mode must be explicit and non-default.
- All filesystem paths derived from user/content metadata MUST use strict input validation (reject `..`, separators, empty segments).
- Never ship insecure defaults in production code paths — gate behind explicit opt-in with startup warnings.
- Samples must not regress auth posture — if auth exists, it must work out-of-box or be explicitly disabled with documentation.

### 7. Mode Parity: Sync/async/stream variants must behave identically
- **If a feature exists in multiple invocation modes (sync/async/stream), semantics must match across all modes.**
- Error handling, lifecycle events, observability, and completion behavior must be consistent.
- If a mode has intentionally different behavior, document it explicitly and test it.
- When adding a feature to one path, verify it works in all paths.

### Testing & CI Quality Gates
- Every P0/P1 bugfix MUST include a regression test reproducing the failing scenario.
- No placeholder/no-op tests are allowed in required CI matrices.
- Flaky-test quarantine requires owner + expiry + tracking issue; quarantined tests must run in a scheduled lane.
- Workflow path filters MUST include all behavior-affecting scripts/config locations for the feature area.
- E2E tests MUST assert the specific feature under test, not just "server started".
- Tests must assert the specific behavior they claim to verify — `expect(true).toBe(true)` is forbidden.

### Correctness Review Checklist
Before committing, verify these for every changed file:

1. **Ownership** — Does this code close/release/shutdown only resources it created?
2. **Terminal paths** — Are success/failure/cancel/timeout/reject all handled symmetrically?
3. **Backpressure** — Are queue-full, rejected-execution, and partial-write signals handled (not ignored)?
4. **Boundaries** — Are path/URL/shell/wire/content-type inputs validated, encoded, and framed correctly?
5. **Runtime truth** — Are capabilities and info based on confirmed runtime state (not config/classpath)?
6. **Authorization** — Do all mutating/admin endpoints require authn/authz?
7. **Mode parity** — If sync/async/stream variants exist, do they behave consistently?

## Testing

### Running Tests
```bash
# Run all tests in a specific module
./mvnw test -pl modules/cpr

# Run a single test class
./mvnw test -pl modules/cpr -Dtest=BroadcasterTest

# Run a single test method
./mvnw test -pl modules/cpr -Dtest=BroadcasterTest#testBroadcast

# Run with debug output
./mvnw test -pl modules/cpr -Dtest=BroadcasterTest -Dsurefire.useFile=false
```

### Test Notes
- Surefire uses `--add-opens` for concurrent locks (configured in root pom.xml)
- Some tests excluded on JDK 25+ (BlockingIOCometSupport incompatibility)
- `JSR356WebSocketTest` excluded (Mockito cannot mock sealed interfaces on JDK 21+)
- `BroadcasterCacheTest` excluded (BlockingIOCometSupport incompatibility)
- JUnit 5 is the test framework for all modules
- Spring Boot starter uses JUnit 5 via `spring-boot-starter-test`
- Quarkus extension uses JUnit 5 via `quarkus-junit5`

## Pre-Commit Validation

### Quick Iteration (during development)
```bash
# 1. Build the module you changed
./mvnw compile -pl modules/cpr

# 2. Run targeted tests
./mvnw test -pl modules/cpr -Dtest=YourTest
```

### Before Committing
```bash
# 1. Compile and verify ZERO warnings (mandatory — do not skip)
./mvnw compile -pl modules/cpr

# 2. Full build of changed module with tests
./mvnw install -pl modules/cpr

# 3. Verify checkstyle and PMD pass
./mvnw checkstyle:checkstyle pmd:check -pl modules/cpr
```

**Do NOT commit or push if the build produces warnings.** Treat compiler warnings, deprecation warnings, and static analysis warnings as errors. Fix them before committing. The compiler runs with `-Xlint:all,-processing,-serial` and Checkstyle enforces `UnusedImports`/`RedundantImport` — both will catch common issues.

### Before Pushing
The pre-push hook blocks `git push` unless you run the validation script first:
```bash
# Full build + tests (recommended)
./scripts/pre-push-validate.sh

# Compile only (faster iteration)
./scripts/pre-push-validate.sh --fast
```
The script stamps a marker valid for 30 minutes. The pre-push hook checks the marker is fresh and matches the current commit.

**Always cancel previous GitHub Actions runs on the branch before pushing** to avoid runner congestion:
```bash
gh run list --branch $(git branch --show-current) --json databaseId,status -q '.[] | select(.status == "queued" or .status == "in_progress") | .databaseId' | xargs -I{} gh run cancel {}
```
This frees up runners for the new push. Without this, stale queued runs accumulate and block CI for all branches.

### Before Merging / PR
```bash
# Full build with all checks
./mvnw install
```

## Spring Boot Starter Notes
- Target: Spring Boot 4.0.2, Spring Framework 7.0
- Set object factory BEFORE init()
- Expose AtmosphereFramework bean but NOT BroadcasterFactory
- Override parent POM's SLF4J/Logback versions for Spring Boot 4 compatibility

## Quarkus Extension Notes
- Target: Quarkus 3.21+
- Config prefix: `quarkus.atmosphere.*`
- `loadOnStartup` must be > 0 (Quarkus skips if <=0)
- Use `BUILD_AND_RUN_TIME_FIXED` for config used in `@BuildStep`
- `Recorder` cannot pass `Class<?>` objects; use class name strings

## atmosphere.js (TypeScript Client)
- Location: `atmosphere.js/`
- Package manager: npm (uses package-lock.json)
- Build: `npm run build`
- Test: `npm test`
- Version: 5.0.0

## Sample Applications

Samples in `samples/` are **the first thing users see** — they must be production-quality, not stubs.

### Rules for Samples
- **Actually use the integration you claim to demonstrate.** If a sample is called `spring-boot-embabel-chat`, it MUST use the real Embabel `AgentPlatform` API — not fake it with a wrapper around a raw LLM client. If you can't make the real integration work, say so and ask for help.
- **No mock/stub implementations disguised as real code.** Comments like "in a real app, you would call X" are a red flag — the sample should BE the real app.
- **Read the third-party library's actual API** (use `javap`, inspect JARs, read source) before writing integration code. Do not guess at APIs.
- **Each sample must compile.** Run `./mvnw compile -pl samples/<name>` before committing.
- **Each sample must have a README.md** explaining what it does, how to run it, and what the key code does.

### Third-Party Integrations
When writing code that integrates with an external library (Embabel, LangChain4j, Spring AI, etc.):
1. **Inspect the actual dependency JARs** in `~/.m2/repository/` to find real class names and method signatures
2. **Use `javap -public`** to read the public API of key classes
3. **Never invent API calls** — if `agentPlatform.runAgent(prompt)` doesn't exist, don't write it
4. **Check for auto-configuration** — most Spring ecosystem libraries provide starter/autoconfigure modules; use them instead of manual wiring

## Getting Help
- Always ask for clarification rather than making assumptions
- If you're having trouble with something, stop and ask for help
