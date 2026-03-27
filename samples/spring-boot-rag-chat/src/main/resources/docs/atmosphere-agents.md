# Atmosphere Agent Framework

## Overview

The `@Agent` annotation is Atmosphere's unified abstraction for building AI-powered agents. A single annotated class can serve browsers over WebSocket, expose tools via MCP, accept tasks from other agents via A2A, stream state to frontends via AG-UI, and route messages to Slack, Telegram, or Discord — all without changing a line of code.

## @Agent Annotation

```java
@Agent(name = "my-agent",
       skillFile = "prompts/my-skill.md",
       description = "What this agent does")
public class MyAgent {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

Key attributes:
- **name**: Agent identifier, used in the URL path `/atmosphere/agent/{name}`
- **skillFile**: Classpath resource containing the system prompt and metadata
- **description**: Human-readable description for protocol metadata
- **headless**: When true, disables WebSocket UI handler for A2A/MCP-only agents

## Slash Commands (@Command)

Commands are routed before the LLM pipeline — messages starting with the command prefix bypass AI and execute the method directly.

```java
@Command(value = "/help", description = "Show available commands")
public String help() {
    return "Available commands: /help, /status";
}
```

## AI Tools (@AiTool)

Tools are functions the LLM can call during response generation. They enable structured data access and actions.

```java
@AiTool(name = "get_weather",
        description = "Get current weather for a city")
public String getWeather(
        @Param(value = "city", description = "City name") String city) {
    return weatherService.lookup(city);
}
```

## Multi-Agent Orchestration (@Coordinator)

A coordinator manages a fleet of agents with sequential, parallel, or pipeline execution patterns.

```java
@Coordinator(name = "ceo", skillFile = "prompts/ceo-skill.md")
@Fleet({
    @AgentRef(type = ResearchAgent.class),
    @AgentRef(type = WriterAgent.class)
})
public class CeoCoordinator {
    @Prompt
    public void orchestrate(String message, StreamingSession session, AgentFleet fleet) {
        var research = fleet.call("research", "analyze", Map.of("topic", message));
        var results = fleet.parallel(research);
        session.send(results.get("research").output());
    }
}
```

## Protocol Support

Agents are automatically exposed via multiple protocols based on classpath detection:
- **WebSocket/SSE**: Real-time browser communication
- **MCP** (Model Context Protocol): Expose tools and resources to AI editors (Claude Desktop, VS Code, Cursor)
- **A2A** (Agent-to-Agent): JSON-RPC task delegation between agents
- **AG-UI**: SSE event streaming for CopilotKit-compatible frontends

## Agent vs AiEndpoint

| Feature | @AiEndpoint | @Agent |
|---------|-------------|--------|
| AI chat | Yes | Yes |
| Slash commands | No | Yes |
| Skill file | No | Yes |
| MCP/A2A/AG-UI | No | Automatic |
| Channel routing | No | Yes (Slack, Telegram, etc.) |
