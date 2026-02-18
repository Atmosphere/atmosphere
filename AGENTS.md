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
This enables pre-commit and commit-msg hooks. Sessions get archived/revived, so this must run EVERY time you start working.

**NEVER use `--no-verify` when committing or pushing.** The hooks enforce:
- Apache 2.0 copyright headers on all Java source files
- Commit message format (max 2 lines, conventional commits recommended)
- No AI-generated commit signatures

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

### Branch Strategy
- Main branch: `main` (development), `atmosphere-2.6.x` (legacy)
- Feature branches for new features
- Bug fixes go directly to `main`

## Project Structure

```
atmosphere/
├── buildtools/                    (checkstyle, PMD rulesets)
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
└── pom.xml                        (4.0.0-SNAPSHOT, JDK 21)
```

### Key Artifacts
| Module | ArtifactId | Description |
|--------|-----------|-------------|
| Core | `atmosphere-runtime` | Main framework JAR |
| Spring Boot | `spring-boot-starter-atmosphere` | Spring Boot 4.0 auto-configuration |
| Quarkus Runtime | `atmosphere-quarkus-extension` | Quarkus 3.21+ runtime |
| Quarkus Deployment | `atmosphere-quarkus-extension-deployment` | Quarkus build-time processing |

## Writing Code

- Java version: **21** (configured in root pom.xml `<release>21</release>`)
- License: Apache 2.0
- All Java files MUST have the copyright header (enforced by pre-commit hook)
- Commit without AI assistant-related commit messages
- Do not add AI-generated commit text in commit messages
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
- Existing tests use TestNG; new tests in starter/quarkus modules use JUnit 5

### Build Enforcement
- Checkstyle: runs in `validate` phase (failsOnError=true)
- PMD: runs in `validate` phase (failsOnError=true)
- Both can be skipped with `-Pfastinstall` for local iteration

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
- TestNG is the test framework for core modules
- JUnit 5 via `spring-boot-starter-test` for the Spring Boot starter
- JUnit 5 via `quarkus-junit5` for the Quarkus extension

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
# 1. Full build of changed module with tests
./mvnw install -pl modules/cpr

# 2. Verify checkstyle and PMD pass
./mvnw checkstyle:checkstyle pmd:check -pl modules/cpr
```

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
- Skip checkstyle and PMD in this module

## Quarkus Extension Notes
- Target: Quarkus 3.21+
- Config prefix: `quarkus.atmosphere.*`
- `loadOnStartup` must be > 0 (Quarkus skips if <=0)
- Skip checkstyle and PMD in this module
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
