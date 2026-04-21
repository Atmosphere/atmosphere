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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.annotation.Command;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG-powered knowledge base agent.
 *
 * <p>Upgrades the basic RAG endpoint into a full {@code @Agent} with:</p>
 * <ul>
 *   <li>Slash commands for quick knowledge base inspection</li>
 *   <li>AI tools the LLM can call for targeted document search and retrieval</li>
 *   <li>Automatic RAG context augmentation via the framework's interceptor chain</li>
 * </ul>
 *
 * <p>The LLM gets both automatic context (via ContextProvider) and explicit
 * tools for multi-hop reasoning — it can search, read specific documents,
 * refine its query, and search again before composing an answer.</p>
 */
@Agent(name = "rag-assistant",
        skillFile = "skill:rag-assistant",
        description = "Knowledge base assistant — answers questions about the Atmosphere Framework using RAG retrieval")
public class RagAgent {

    private static final Logger logger = LoggerFactory.getLogger(RagAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected to RAG assistant (broadcaster: {})",
                resource.uuid(), resource.getBroadcaster().getID());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("RAG prompt: {}", message);
        // Always through the pipeline: DemoAgentRuntime handles the no-key
        // fallback, the real runtime drives RAG when LLM_API_KEY is set.
        session.stream(message);
    }

    // ── Slash Commands ─────────────────────────────

    @Command(value = "/sources",
            description = "List all loaded knowledge base documents")
    public String sources() {
        var docs = KnowledgeBase.instance().documents();
        if (docs.isEmpty()) {
            return "No documents loaded. Add documents to src/main/resources/docs/ and restart.";
        }
        var sb = new StringBuilder("Knowledge Base (" + docs.size() + " documents):\n\n");
        for (int i = 0; i < docs.size(); i++) {
            var doc = docs.get(i);
            var wordCount = doc.content().split("\\s+").length;
            sb.append(i + 1).append(". ").append(doc.source())
                    .append(" (").append(wordCount).append(" words)\n");
        }
        sb.append("\nAsk me anything about these documents, or use /help for more commands.");
        return sb.toString();
    }

    @Command(value = "/help",
            description = "Show available commands and capabilities")
    public String help() {
        return """
                RAG Assistant — Commands:

                /sources  — List loaded knowledge base documents
                /help     — Show this help message

                AI Tools (used automatically when needed):
                  search_knowledge_base  — Search documents by topic
                  list_sources           — Enumerate available documents
                  get_document_excerpt   — Read a specific document in full

                Just ask a question and I'll find the answer in the knowledge base.""";
    }

    // ── AI Tools ───────────────────────────────────

    @AiTool(name = "search_knowledge_base",
            description = "Search the knowledge base for documents relevant to a query. "
                    + "Use this to find specific information before answering the user.")
    public String searchKnowledgeBase(
            @Param(value = "query",
                    description = "The search query — use specific keywords for best results")
            String query,
            @Param(value = "max_results",
                    description = "Maximum number of documents to return (1-5)",
                    required = false)
            String maxResults) {

        int limit;
        try {
            limit = maxResults != null ? Integer.parseInt(maxResults) : 3;
        } catch (NumberFormatException e) {
            limit = 3;
        }
        limit = Math.max(1, Math.min(5, limit));

        var results = KnowledgeBase.instance().search(query, limit);
        if (results.isEmpty()) {
            return "No documents matched the query: " + query;
        }

        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            var doc = results.get(i);
            sb.append("--- Document ").append(i + 1)
                    .append(" (source: ").append(doc.source())
                    .append(", relevance: ").append(String.format("%.0f%%", doc.score() * 100))
                    .append(") ---\n");
            sb.append(doc.content()).append("\n\n");
        }
        return sb.toString();
    }

    @AiTool(name = "list_sources",
            description = "List all available knowledge base documents with their sizes. "
                    + "Use this to understand what information is available before searching.")
    public String listSources() {
        var docs = KnowledgeBase.instance().documents();
        if (docs.isEmpty()) {
            return "Knowledge base is empty.";
        }
        var sb = new StringBuilder("Available documents:\n");
        for (var doc : docs) {
            var wordCount = doc.content().split("\\s+").length;
            sb.append("- ").append(doc.source())
                    .append(" (").append(wordCount).append(" words)\n");
        }
        return sb.toString();
    }

    @AiTool(name = "get_document_excerpt",
            description = "Get the full content of a specific knowledge base document by source name. "
                    + "Use this when you need to read a particular document in detail.")
    public String getDocumentExcerpt(
            @Param(value = "source",
                    description = "Document source path (e.g. 'docs/atmosphere-overview.md')")
            String source) {

        var doc = KnowledgeBase.instance().findBySource(source);
        if (doc == null) {
            return "Document not found: " + source
                    + ". Use list_sources to see available documents.";
        }
        return "--- " + doc.source() + " ---\n" + doc.content();
    }
}
