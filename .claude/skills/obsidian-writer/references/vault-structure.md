# atmosphere-vault Structure Reference

## Directory Map

```
atmosphere-vault/
  Architecture/            System design, component diagrams
    ADRs/                  Architecture Decision Records (ADR-NNN format)
  APIs/                    API endpoint docs, SDK references
  Development/             Developer guides, setup docs
    Runbooks/              Operational runbooks and on-call procedures
  Methodology/             Processes, workflows, team practices
  Claude Outputs/          Claude Code session outputs (via claude_docs symlink)
  Claude Plans/            Planning documents from Claude sessions
  Templates/               Templater templates: ADR.md, Plan.md, Runbook.md
  Assets/                  Images, diagrams, attachments
```

## Frontmatter Field Reference

| Doc Type | Field | Required | Values / Notes |
|----------|-------|----------|----------------|
| All | `date` | Yes | `YYYY-MM-DD` ISO 8601 |
| All | `tags` | Yes | Array — always include `atmosphere` plus type tag |
| ADR | `status` | Yes | `proposed`, `accepted`, `deprecated`, `superseded` |
| ADR | `supersedes` | No | Wikilink to replaced ADR |
| Runbook | `service` | Yes | Service / module name (e.g., `atmosphere-runtime`, `spring-boot-starter`) |
| Runbook | `severity` | Yes | `P0`, `P1`, `P2`, `P3` |
| Plan | `status` | Yes | `draft`, `active`, `completed` |
| Plan | `milestone` | No | Milestone or sprint name |
| API doc | `service` | Yes | Service or module name |
| API doc | `version` | No | API version string |
| Guide | — | — | Only `date` and `tags` required |
| Session output | — | — | Only `date` and `tags` required |

## Type Tags

| Doc Type | Primary Tag | Secondary Tags |
|----------|-------------|----------------|
| ADR | `adr` | |
| Runbook | `runbook` | `sre` |
| Plan | `plan` | |
| API doc | `api` | |
| Guide / how-to | `guide` | `<domain>` (e.g., `runtime`, `spring`, `quarkus`) |
| Session output | `claude-output` | |
| Methodology | `methodology` | |

## Naming Conventions

| Doc Type | Pattern | Example |
|----------|---------|---------|
| ADR | `ADR-NNN Short Title.md` | `ADR-042 Adopt Virtual Threads.md` |
| Runbook | `<Service> <Topic>.md` | `Atmosphere Runtime WebSocket Backpressure.md` |
| Plan | `<Topic> Plan.md` | `Quarkus Extension Refactor Plan.md` |
| API doc | `<Service> API.md` or `<Endpoint>.md` | `Atmosphere CPR API.md` |
| Guide | `<Topic> Guide.md` or `How to <Topic>.md` | `How to Set Up Dev Environment.md` |

## Templates

Templates live in `Templates/` and are applied by the Templater plugin.

- `Templates/ADR.md` — ADR with frontmatter, Context, Decision, Consequences sections
- `Templates/Plan.md` — Plan with frontmatter, Goals, Approach, Tasks, Risks sections
- `Templates/Runbook.md` — Runbook with frontmatter, Prerequisites, Steps, Verification, Rollback sections

To use a template via obsidian-cli, read the template first then compose the note content
based on its structure:

```
obsidian read file="ADR"
```

## Wikilink Patterns

Always use wikilinks for internal references — never relative or absolute file paths:

```markdown
See [[ADR-042 Adopt Virtual Threads]] for the decision rationale.
Related: [[Atmosphere Runtime WebSocket Backpressure]] runbook.
Supersedes: [[ADR-038 Custom Thread Pool]].
```

Wikilinks display with the note title by default. Use `|` for custom display text:

```markdown
[[ADR-042 Adopt Virtual Threads|ADR-042]]
```
