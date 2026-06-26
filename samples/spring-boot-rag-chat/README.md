# Atmosphere RAG Agent Sample

A knowledge base assistant for the Atmosphere Framework. Two complementary
surfaces: a **RAG chat endpoint** the console connects to (automatic
`ContextProvider` retrieval, protected by the default-on injection-safety
screen) and a richer **`@Agent`** with AI tools and slash commands.

## What It Does

1. **`@AiEndpoint`** (`RagChatEndpoint`, the console default at `/atmosphere/ai-chat`) — automatic RAG: every turn retrieves documents through a `ContextProvider` and injects them into the prompt
2. **RAG injection-safety screen** — on by default; retrieved documents are checked for indirect prompt injection (OWASP Agentic A04) and dropped before they reach the LLM
3. **`@Agent`** (`RagAgent` at `/atmosphere/agent/rag-assistant`) with a skill file defining the assistant persona
4. **Slash commands** (`/sources`, `/help`) for instant responses that bypass the LLM
5. **AI tools** (`search_knowledge_base`, `list_sources`, `get_document_excerpt`) the LLM can call for multi-hop reasoning
6. **Real-time streaming** over WebSocket/SSE

When a Spring AI embedding model is configured (API key present), each Markdown
document is also chunked with `RagChunker` and indexed into a `SimpleVectorStore`
for semantic search; chunk metadata preserves the source document and offsets so
citations point to the right passage.

## Architecture

```
Browser (atmosphere.js)
    |
    +-- /atmosphere/ai-chat (console default)
    |     @AiEndpoint (RagChatEndpoint.java)
    |       @Prompt --> RAG pipeline:
    |         1. KnowledgeBaseContextProvider retrieves docs
    |         2. SafetyContextProvider screens them (drops injections) <-- default-on
    |         3. LLM generates response
    |
    +-- /atmosphere/agent/rag-assistant
          @Agent (RagAgent.java)
            +-- /sources, /help  --> Instant response (no LLM)
            +-- @AiTool methods  --> LLM calls search_knowledge_base,
                                     list_sources, get_document_excerpt
```

## How to Run

### Without API Key (Demo Mode)

```bash
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

Open http://localhost:8080/atmosphere/console/. Try `/sources` and `/help` for instant commands.

### With Gemini API Key

```bash
export LLM_API_KEY=your-gemini-api-key
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

### With OpenAI API Key

```bash
export LLM_MODE=remote
export LLM_MODEL=gpt-4o-mini
export LLM_BASE_URL=https://api.openai.com/v1
export LLM_API_KEY=sk-your-openai-key
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

## RAG Injection Safety (on by default)

Atmosphere wraps every `@AiEndpoint` `ContextProvider` with an injection-safety
screen, so retrieved documents are checked for indirect prompt injection (OWASP
Agentic Top-10 A04) **before** they reach the LLM. It is on by default,
fail-closed, and needs no dependencies — the default `RULE_BASED` classifier
runs in sub-milliseconds.

To make it visible, `KnowledgeBaseContextProvider`'s retrieval source carries one
*simulated attacker-poisoned document* (`docs/community-security-tips.md`) whose
trailing line is an injection ("Ignore all previous instructions…"). Ask the chat
**"how do I secure Atmosphere?"** and the server log shows the framework dropping
it before the prompt is built:

```
WARN o.a.a.g.rag.SafetyContextProvider : SafetyContextProvider dropping document from 'docs/community-security-tips.md': injection probe 'instruction-override' matched: 'Ignore all previous instructions'
```

The console's `/api/console/info` also reports the live screen as runtime truth:

```json
{ "ragSafety": { "active": true, "tier": "RULE_BASED", "breach": "DROP" } }
```

Tune or disable it (all keys default to the values shown):

```properties
atmosphere.ai.rag.safety.enabled=true        # set false to turn the screen off
atmosphere.ai.rag.safety.tier=RULE_BASED      # or EMBEDDING_SIMILARITY / LLM_CLASSIFIER
atmosphere.ai.rag.safety.on-breach=DROP       # or FLAG / SANITIZE
atmosphere.ai.rag.safety.fail-open=false      # admit on classifier error
```

Set `atmosphere.ai.rag.safety.enabled=false` and ask the same question to see the
poisoned document flow through unscreened.

> The screen covers the `ContextProvider` retrieval path. The `@Agent`'s explicit
> `@AiTool` search is a separate mechanism over the (clean) shared `KnowledgeBase`.

## Key Files

| File | Description |
|------|-------------|
| `RagChatEndpoint.java` | `@AiEndpoint` (console default) — automatic RAG via `ContextProvider`, screened by the injection-safety filter |
| `KnowledgeBaseContextProvider.java` | RAG `ContextProvider` over the knowledge base; carries the simulated poisoned document the screen drops |
| `RagAgent.java` | `@Agent` with slash commands, AI tools, and prompt handler |
| `KnowledgeBase.java` | Thread-safe singleton for document storage and word-overlap search |
| `VectorStoreConfig.java` | Loads docs into KnowledgeBase + chunked Spring AI VectorStore |
| `LlmConfig.java` | Bridges Spring properties to `AiConfig` |
| `docs/*.md` | Knowledge base documents about Atmosphere |
| `prompts/rag-agent-skill.md` | Skill file defining the agent persona and tools |

## Slash Commands

| Command | Description |
|---------|-------------|
| `/sources` | List all loaded knowledge base documents with word counts |
| `/help` | Show available commands and AI tool descriptions |

## AI Tools

| Tool | Description |
|------|-------------|
| `search_knowledge_base` | Search documents by keyword/topic (1-5 results) |
| `list_sources` | Enumerate all available documents with sizes |
| `get_document_excerpt` | Read a specific document in full by source path |

## Knowledge Base

Five documentation files in `src/main/resources/docs/`:

- `atmosphere-overview.md` — Framework overview and key features
- `atmosphere-transports.md` — Transport protocols (WebSocket, SSE, long-polling, gRPC)
- `atmosphere-ai-module.md` — AI module (@AiEndpoint, StreamingSession, ContextProvider)
- `atmosphere-getting-started.md` — Getting started with Maven and examples
- `atmosphere-agents.md` — Agent framework (@Agent, @Command, @AiTool, @Coordinator)

`KnowledgeBase` keeps full documents for the explicit `@AiTool` methods.
`RagChatEndpoint`'s automatic RAG retrieves over the knowledge base with the
built-in word-overlap retriever, so it works with no API key. When an embedding
model is configured, `VectorStoreConfig` additionally indexes retrieval-sized
chunks into a Spring AI `SimpleVectorStore` for semantic search.
