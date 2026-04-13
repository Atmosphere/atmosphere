# Atmosphere Project Generator

This directory contains the **compose generator** — a skill-file-driven scaffold
that emits a parametric multi-module Atmosphere project (parent POM + N coordinator
modules + M agent modules, optionally with Docker Compose deployment).

For scaffolding a single-module starter project from an existing sample, use the
Atmosphere CLI instead:

```bash
atmosphere new my-app --template ai-chat
# or clone the full sample verbatim
atmosphere install spring-boot-ai-chat
```

Both paths sparse-clone from the `samples/` directory in this repo via
`cli/samples.json`, so every sample listed in that registry is available as a
starter with zero template maintenance.

## Compose generator

`ComposeGenerator.java` (invoked via JBang) consumes one or more
[skill files](https://docs.anthropic.com/en/docs/agents/skills) and emits a
multi-module project in which each skill becomes either a `@Coordinator` or an
`@Agent` module.

### Prerequisites

- [JBang](https://www.jbang.dev/download/) installed (`sdk install jbang` or `brew install jbang`)
- JDK 21+

### Usage

The CLI wrapper is the recommended entry point:

```bash
atmosphere compose skills/research/ skills/writer/
atmosphere compose --name my-fleet --protocol a2a research.md writer.md
```

See `atmosphere compose --help` for all options.

### Direct invocation (advanced)

```bash
jbang generator/ComposeGenerator.java \
  --name my-fleet \
  --group com.example \
  --protocol a2a \
  --transport websocket \
  --skills coordinator.md,analyst.md
```

### Generated project structure

```
my-fleet/
├── pom.xml                    (parent POM)
├── docker-compose.yml         (optional, with --deploy docker-compose)
├── Dockerfile                 (optional, with --deploy docker-compose)
├── README.md
├── coordinator-<name>/
│   ├── pom.xml
│   └── src/main/java/.../Coordinator.java
└── agent-<name>/
    ├── pom.xml
    └── src/main/java/.../Agent.java
```

## Tests

`ComposeGeneratorTest.java` runs via JBang:

```bash
jbang generator/ComposeGeneratorTest.java
```

The `test-compose.sh` integration script exercises the end-to-end generated
project (build + run smoke test).
