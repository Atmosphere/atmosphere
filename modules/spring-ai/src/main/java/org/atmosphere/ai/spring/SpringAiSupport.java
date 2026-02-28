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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.StreamingSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * {@link AiSupport} implementation backed by Spring AI's {@link ChatClient}.
 *
 * <p>Auto-detected when {@code spring-ai-client-chat} is on the classpath.
 * The {@link ChatClient} must be configured via {@link #setChatClient} — typically
 * done by {@link AtmosphereSpringAiAutoConfiguration}.</p>
 */
public class SpringAiSupport implements AiSupport {

    private static volatile ChatClient chatClient;

    @Override
    public String name() {
        return "spring-ai";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.springframework.ai.chat.client.ChatClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // ChatClient is configured externally via Spring auto-configuration
    }

    /**
     * Set the {@link ChatClient} to use for streaming. Called by the
     * Spring auto-configuration.
     */
    public static void setChatClient(ChatClient client) {
        chatClient = client;
    }

    @Override
    public void stream(AiRequest request, StreamingSession session) {
        var client = chatClient;
        if (client == null) {
            throw new IllegalStateException(
                    "SpringAiSupport: ChatClient not configured. "
                            + "Ensure spring-ai-openai or another Spring AI model starter is on the classpath.");
        }

        session.progress("Connecting to AI model...");

        var promptSpec = client.prompt(request.message());
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            promptSpec.system(request.systemPrompt());
        }

        Flux<ChatResponse> flux = promptSpec.stream().chatResponse();
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
}
