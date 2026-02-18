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

/**
 * Spring AI adapter that bridges {@link ChatClient}'s Flux-based streaming
 * to an Atmosphere {@link StreamingSession}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * var session = StreamingSessions.start(resource);
 * adapter.stream(chatClient, "Tell me about Atmosphere", session);
 * }</pre>
 */
public class SpringAiStreamingAdapter implements AiStreamingAdapter<SpringAiStreamingAdapter.ChatRequest> {

    @Override
    public String name() {
        return "spring-ai";
    }

    @Override
    public void stream(ChatRequest request, StreamingSession session) {
        session.progress("Connecting to AI model...");
        request.client().prompt(request.prompt())
                .stream()
                .chatResponse()
                .doOnNext(response -> {
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
     * Convenience method to start streaming directly.
     *
     * @param client  the Spring AI ChatClient
     * @param prompt  the user prompt
     * @param session the Atmosphere streaming session
     */
    public void stream(ChatClient client, String prompt, StreamingSession session) {
        stream(new ChatRequest(client, prompt), session);
    }

    /**
     * Request record wrapping a ChatClient and prompt.
     */
    public record ChatRequest(ChatClient client, String prompt) {
    }
}
