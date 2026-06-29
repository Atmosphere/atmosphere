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
package org.atmosphere.samples.springboot.springaiadvisors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A deterministic, fully offline {@link ChatModel} that echoes the user's
 * message back. It sits at the <em>end</em> of the Spring AI advisor chain so
 * the demonstration needs no API key, no network, and no embedding model — the
 * advisor wiring this sample proves is identical regardless of which model
 * terminates the chain.
 *
 * <p>This is NOT a stub standing in for the integration under test: the
 * integration under test is the Spring AI {@code ChatClient} + advisor chain +
 * {@code SpringAiAgentRuntime.setChatClient(...)} binding, all of which are
 * real. The model is local purely so the advisors run deterministically.
 * To talk to a real provider, swap this for any real {@code ChatModel} (e.g.
 * {@code OpenAiChatModel}) in {@link BoundChatClientConfig} — the
 * {@code defaultAdvisors(...)} and per-request advisors fire exactly the
 * same way.</p>
 */
public final class LocalEchoChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(echo(prompt)))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    private static String echo(Prompt prompt) {
        var userMessage = prompt.getUserMessage();
        var text = userMessage != null ? userMessage.getText() : prompt.getContents();
        return "[local-echo] " + (text == null ? "" : text);
    }
}
