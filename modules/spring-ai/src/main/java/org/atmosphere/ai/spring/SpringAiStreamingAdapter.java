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
package org.atmosphere.ai.spring;

import org.atmosphere.ai.AiStreamingAdapter;
import org.atmosphere.ai.StreamingSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * Spring AI adapter that bridges {@link ChatClient}'s Flux-based streaming
 * to an Atmosphere {@link StreamingSession}.
 *
 * <p>Basic usage:</p>
 * <pre>{@code
 * var session = StreamingSessions.start(resource);
 * adapter.stream(chatClient, "Tell me about Atmosphere", session);
 * }</pre>
 *
 * <p>With advisors (RAG, logging, etc.):</p>
 * <pre>{@code
 * adapter.stream(chatClient, "Tell me about Atmosphere", session,
 *     spec -> spec.advisors(myRagAdvisor, myLoggingAdvisor)
 *                 .system("You are a helpful assistant"));
 * }</pre>
 */
public class SpringAiStreamingAdapter implements AiStreamingAdapter<SpringAiStreamingAdapter.ChatRequest> {

    @Override
    public String name() {
        return "spring-ai";
    }

    @Override
    @SuppressWarnings("null")
    public void stream(ChatRequest request, StreamingSession session) {
        session.progress("Connecting to AI model...");

        var promptSpec = request.client().prompt(request.prompt());
        if (request.customizer() != null) {
            request.customizer().accept(promptSpec);
        }

        subscribeToStream(promptSpec.stream().chatResponse(), session);
    }

    /**
     * Convenience method to start streaming directly.
     *
     * @param client  the Spring AI ChatClient
     * @param prompt  the user prompt
     * @param session the Atmosphere streaming session
     */
    public void stream(ChatClient client, String prompt, StreamingSession session) {
        stream(new ChatRequest(client, prompt, null), session);
    }

    /**
     * Stream with a customizer for advisors, system prompts, tools, etc.
     *
     * @param client     the Spring AI ChatClient
     * @param prompt     the user prompt
     * @param session    the Atmosphere streaming session
     * @param customizer configures the prompt spec (advisors, system prompt, tools)
     */
    public void stream(ChatClient client, String prompt, StreamingSession session,
                       Consumer<ChatClient.ChatClientRequestSpec> customizer) {
        stream(new ChatRequest(client, prompt, customizer), session);
    }

    /**
     * Stream with pre-configured advisors.
     *
     * @param client   the Spring AI ChatClient
     * @param prompt   the user prompt
     * @param session  the Atmosphere streaming session
     * @param advisors one or more Spring AI advisors (RAG, logging, memory, etc.)
     */
    public void stream(ChatClient client, String prompt, StreamingSession session,
                       Advisor... advisors) {
        stream(client, prompt, session, spec -> spec.advisors(advisors));
    }

    private void subscribeToStream(Flux<ChatResponse> flux, StreamingSession session) {
        flux.doOnNext(response -> {
                    if (response.getResult() != null
                            && response.getResult().getOutput() != null
                            && response.getResult().getOutput().getText() != null) {
                        session.send(response.getResult().getOutput().getText());
                    }
                })
                .doOnComplete(session::complete)
                .doOnError(session::error)
                .subscribe();
    }

    /**
     * Request record wrapping a ChatClient, prompt, and optional customizer.
     *
     * @param client     the Spring AI ChatClient
     * @param prompt     the user prompt
     * @param customizer optional customizer for the prompt spec (advisors, system, tools)
     */
    public record ChatRequest(
            ChatClient client,
            String prompt,
            Consumer<ChatClient.ChatClientRequestSpec> customizer
    ) {
        public ChatRequest(ChatClient client, String prompt) {
            this(client, prompt, null);
        }
    }
}
