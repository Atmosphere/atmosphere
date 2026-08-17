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

Each Markdown document is chunked with `RagChunker`, indexed into a
`SimpleVectorStore`, and **retrieved with real `VectorStore.similaritySearch`** on every
turn — by both the endpoint's automatic RAG (via `SpringAiVectorStoreContextProvider`) and
the agent's `search_knowledge_base` tool. Chunk metadata preserves the source document and
offsets so citations point to the right passage.

Retrieval needs a reachable **embedding** endpoint, which is separate from the chat model.
If embedding the corpus fails the sample still boots, logs a warning, and falls back to the
built-in word-overlap retriever, so demo mode keeps working.

### Grounded retrieval with local Ollama (no API key)

```bash
ollama pull nomic-embed-text
SPRING_AI_BASE_URL=http://localhost:11434/v1 \
EMBEDDING_MODEL=nomic-embed-text \
LLM_MODE=local LLM_MODEL=qwen2.5:7b-instruct-q4_K_M \
  ./mvnw spring-boot:run -pl samples/spring-boot-rag-chat
```

`SPRING_AI_BASE_URL` **includes the `/v1` suffix** — Spring AI 2.0 delegates to the official
`com.openai` Java SDK, whose base URL is `https://api.openai.com/v1` by default and which
appends only the resource path (`/embeddings`, `/chat/completions`).

Confirm retrieval is real rather than plausible-sounding: ask a corpus question and check the
log for `Loaded N chunks into SimpleVectorStore with embeddings`. If you instead see
`No ContextProvider configured` or `VectorStore not configured`, the store was not built.

Set `atmosphere.rag.vector-store.enabled=false` to skip the vector store entirely (the
knowledge base and `@AiTool` search still work).

> **Note (fixed in 4.0.67):** this sample previously excluded
> `OpenAiEmbeddingAutoConfiguration` *and* gated its `VectorStore` on
> `@ConditionalOnBean(EmbeddingModel.class)` from a plain `@Configuration`. Both independently
> prevented the store from ever being created, so answers looked plausible but were never
> grounded in the corpus. See `VectorStoreConfig` and `VectorStoreWiringTest`.

## Architecture

```
Browser (atmosphere.js)
    |
    +-- /atmosphere/ai-chat (console default)
    |     @AiEndpoint (RagChatEndpoint.java)
    |       @Prompt --> RAG pipeline:
    |         1. SpringAiVectorStoreContextProvider (similaritySearch, keyed mode)
    |            + KnowledgeBaseContextProvider (word-overlap fallback + demo doc)
    |            over-fetches 15 candidates (atmosphere.ai.rag.reranker=llm)
    |         2. SafetyContextProvider screens them (drops injections) <-- default-on
    |         3. LlmReranker scores the screened candidates down to top-5
    |            (fails open to retriever order on any error/timeout)
    |         4. LLM generates response
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

## LLM Reranker (over-fetch, then rerank)

This sample opts in to Atmosphere's second-stage reranker
(`atmosphere.ai.rag.reranker=llm` in `application.yml`). Instead of injecting the
retriever's raw top-5, each turn over-fetches 3x the slot (15 candidates,
`atmosphere.ai.rag.overfetch`), and one batched completion on the endpoint's
runtime ranks them by relevance to the question; the top 5 reach the prompt.
Registration logs the active policy:

```
AI endpoint /atmosphere/ai-chat — LLM reranker active: over-fetch x3 then rerank down to top-5
```

Reranking is strictly fail-open: any error, timeout
(`atmosphere.ai.rag.reranker-timeout-ms`, default 10000), or unparseable model
output keeps the original retriever order (trimmed to top-5), so retrieval never
breaks because reranking hiccuped. In keyless demo mode the demo runtime's canned
reply is unparseable, so the sample simply falls back to retriever order — with a
real API key the reranker genuinely reorders the over-fetched candidates. The
injection-safety screen above always runs *before* the reranker sees a candidate.

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

`KnowledgeBase` keeps full documents for the explicit `@AiTool` methods. With an
embedding model configured, `VectorStoreConfig` indexes retrieval-sized chunks
into a Spring AI `SimpleVectorStore` and **both retrieval paths query it with
`similaritySearch`** — the endpoint's automatic RAG and `search_knowledge_base`.
With no API key, retrieval degrades to the built-in word-overlap retriever so
the demo still works.
