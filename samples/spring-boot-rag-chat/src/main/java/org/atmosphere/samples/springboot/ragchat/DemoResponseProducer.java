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

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates LLM streaming responses for demo/testing purposes.
 * Used when no API key is configured so the sample works out-of-the-box.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * Stream a simulated response word-by-word through the session.
     */
    public static void stream(String userMessage, StreamingSession session) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — set LLM_API_KEY to enable RAG with real embeddings");
            for (var word : words) {
                session.send(word);
                Thread.sleep(50);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("agent") || lower.contains("@agent") || lower.contains("command")) {
            return "The @Agent annotation is Atmosphere's unified abstraction for AI agents. "
                    + "A single class can serve browsers via WebSocket, expose tools via MCP, "
                    + "accept tasks from other agents via A2A, and route messages to Slack or Telegram. "
                    + "Agents support slash commands (@Command) for quick actions and "
                    + "@AiTool methods the LLM can call for structured tasks. "
                    + "Try /sources to see the loaded knowledge base, or /help for all commands. "
                    + "(Source: docs/atmosphere-agents.md)";
        }
        if (lower.contains("transport") || lower.contains("websocket") || lower.contains("sse")) {
            return "Atmosphere supports multiple transports: WebSocket, SSE (Server-Sent Events), "
                    + "long-polling, and gRPC. The framework automatically negotiates the best "
                    + "transport available. WebSocket provides full-duplex communication with the "
                    + "lowest latency, while SSE is HTTP-friendly and handles reconnection automatically. "
                    + "(Source: docs/atmosphere-transports.md)";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "It provides a unified API across WebSocket, SSE, long-polling, and gRPC. "
                    + "This sample demonstrates a RAG Agent — it uses @Agent with AI tools that "
                    + "let the LLM actively search and read the knowledge base rather than just "
                    + "receiving pre-retrieved context. Try /sources to see what's loaded. "
                    + "(Source: docs/atmosphere-overview.md)";
        }
        if (lower.contains("rag") || lower.contains("retrieval") || lower.contains("tool")) {
            return "This RAG Agent combines two retrieval strategies: automatic context augmentation "
                    + "(the framework retrieves relevant docs before the LLM call) and explicit "
                    + "AI tools (search_knowledge_base, list_sources, get_document_excerpt) that "
                    + "the LLM can invoke for multi-hop reasoning. The agent also has slash commands "
                    + "(/sources, /help) that bypass the LLM entirely for instant responses.";
        }
        if (lower.contains("coordinator") || lower.contains("multi-agent") || lower.contains("fleet")) {
            return "The @Coordinator annotation manages a fleet of agents with sequential, parallel, "
                    + "or pipeline execution patterns. Declare the fleet with @Fleet and @AgentRef, "
                    + "inject AgentFleet into your @Prompt method, and orchestrate with plain Java. "
                    + "Agents communicate via A2A (Agent-to-Agent) protocol using JSON-RPC. "
                    + "(Source: docs/atmosphere-agents.md)";
        }
        var docCount = KnowledgeBase.instance().documents().size();
        return "This is a RAG Agent with " + docCount + " documents loaded. "
                + "Try /sources to see the knowledge base, /help for commands, "
                + "or ask about 'agents', 'transports', 'RAG', or 'atmosphere'. "
                + "Set LLM_API_KEY to connect to a real LLM provider with full RAG and tool calling.";
    }
}
