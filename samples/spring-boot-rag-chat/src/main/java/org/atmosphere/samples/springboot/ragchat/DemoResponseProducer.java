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
        if (lower.contains("transport") || lower.contains("websocket") || lower.contains("sse")) {
            return "Atmosphere supports multiple transports: WebSocket, SSE (Server-Sent Events), "
                    + "long-polling, and gRPC. The framework automatically negotiates the best "
                    + "transport available. In RAG mode, this information would come from the "
                    + "vector store. Set LLM_API_KEY to see real RAG in action.";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "It provides a unified API across WebSocket, SSE, long-polling, and gRPC. "
                    + "This sample demonstrates RAG (Retrieval-Augmented Generation) where "
                    + "relevant documents are retrieved from a vector store before querying the LLM.";
        }
        if (lower.contains("rag") || lower.contains("retrieval")) {
            return "RAG (Retrieval-Augmented Generation) enhances LLM responses by first "
                    + "retrieving relevant documents from a knowledge base, then including "
                    + "them as context in the prompt. This sample uses Spring AI's SimpleVectorStore "
                    + "with OpenAI embeddings to search Atmosphere documentation.";
        }
        return "This is a demo response — each word arrives as a separate streaming text. "
                + "Try asking about 'atmosphere', 'transports', or 'RAG'. "
                + "Set LLM_API_KEY to connect to a real LLM provider with RAG.";
    }
}
