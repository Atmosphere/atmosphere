# Skill Files

A skill file is a Markdown document that serves as both the **system prompt** for your agent's LLM and the **metadata source** for protocol bridges (A2A Agent Card, tool cross-referencing).

## How It Works

1. The entire file is passed to the LLM as the system prompt — verbatim.
2. Specific `##` sections are parsed for structured metadata used by `@Agent` internals.

Reference the file from your agent annotation:

```java
@Agent(name = "devops", skillFile = "prompts/devops-skill.md")
public class DevOpsAgent { ... }
```

The file is loaded from the classpath (typically `src/main/resources/prompts/`).

## Sections

All sections are optional. Unrecognized sections are ignored by the parser but still included in the system prompt.

| Section | Purpose |
|---------|---------|
| `# Title` | Agent name / persona. First `#` heading becomes the display name. |
| *(body text)* | Free-form instructions, persona, behavior rules — all sent to the LLM. |
| `## Skills` | Bullet list of capabilities. Exported to A2A Agent Card. |
| `## Tools` | Bullet list of tool names + descriptions. Cross-referenced against `@AiTool` methods at startup — mismatches produce warnings. |
| `## Channels` | Bullet list of enabled channels (`slack`, `telegram`, `web`, etc.). Included in the system prompt and used for routing validation — agents only receive messages from listed channels. |
| `## Guardrails` | Safety rules. Sent to the LLM as part of the system prompt and exported as protocol metadata (A2A Agent Card `guardrails` field, MCP `serverInfo.guardrails`). |

## Example

```markdown
# DevOps Assistant

You are a DevOps assistant that helps teams monitor services,
manage deployments, and respond to incidents.

## Skills
- Monitor service health and performance
- Manage deployments to staging and production

## Tools
- check_service: Check the health status of a specific service
- get_metrics: Get performance metrics (CPU, memory, latency, errors)

## Channels
- slack
- web

## Guardrails
- Never execute production deployments without confirmation
- Always recommend rollback plans before deployments
```

## Tips

- Keep guardrails concrete and actionable — vague rules are ignored by LLMs.
- The `## Tools` section is documentation, not registration. Tools are registered via `@AiTool` in Java. The parser warns if the two lists diverge.
- You can add any custom sections (`## Tone`, `## Examples`, `## Context`) — the parser ignores them but the LLM sees them.

## Real Examples

- [dentist-skill.md](../samples/spring-boot-dentist-agent/src/main/resources/prompts/dentist-skill.md) — Multi-channel dental assistant
- [ceo-skill.md](../samples/spring-boot-multi-agent-startup-team/src/main/resources/prompts/ceo-skill.md) — Multi-agent CEO orchestrator

## CLI: Generate an Agent from a Skill File

```bash
atmosphere new my-agent --skill-file skill.md
```

This scaffolds a Spring Boot project with `@Agent`, `@Command`, and `@AiTool` stubs derived from your skill file's sections.
