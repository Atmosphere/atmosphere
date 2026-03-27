# Atmosphere RAG Agent Sample

A knowledge base agent powered by `@Agent`, AI tools, slash commands, and RAG retrieval. Answers questions about the Atmosphere Framework by actively searching and reading documentation.

## What It Does

This sample demonstrates the progression from a passive RAG chatbot to an active knowledge agent:

1. **@Agent** with a skill file defining the assistant persona
2. **Slash commands** (`/sources`, `/help`) for instant responses that bypass the LLM
3. **AI tools** (`search_knowledge_base`, `list_sources`, `get_document_excerpt`) the LLM can call for multi-hop reasoning
4. **Automatic RAG** via Spring AI VectorStore for context augmentation
5. **Real-time streaming** over WebSocket/SSE

The LLM gets both automatic context (retrieved docs injected before the call) and explicit tools it can invoke to search, read specific documents, refine its query, and search again.

## Architecture

```
Browser (atmosphere.js)
    |
    v
@Agent (RagAgent.java)
    |
    +-- /sources, /help  --> Instant response (no LLM)
    |
    +-- @AiTool methods  --> LLM calls search_knowledge_base,
    |                        list_sources, get_document_excerpt
    |
    +-- @Prompt           --> RAG pipeline:
         |                     1. ContextProvider retrieves docs
         |                     2. LLM generates response
         v
    StreamingSession --> Browser
```

## How to Run

### Without API Key (Demo Mode)

```bash
cd samples/spring-boot-rag-chat
../../mvnw spring-boot:run
```

Open http://localhost:8080. Try `/sources` and `/help` for instant commands.

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

## Key Files

| File | Description |
|------|-------------|
| `RagAgent.java` | `@Agent` with slash commands, AI tools, and prompt handler |
| `KnowledgeBase.java` | Thread-safe singleton for document storage and word-overlap search |
| `VectorStoreConfig.java` | Loads docs into KnowledgeBase + Spring AI VectorStore |
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
