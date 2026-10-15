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
package org.atmosphere.channels;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The raw LLM fallback of a bridge built for one application answers with that
 * application's client and model while another application runs in the JVM,
 * instead of the JVM-wide settings, which then belong to no application.
 */
class ChannelAiBridgeApplicationTest {

    @Test
    void theRawFallbackAnswersWithItsApplicationsClient() throws Exception {
        var first = AiEnvironment.create(key -> null);
        var second = AiEnvironment.create(key -> null);
        try {
            first.configure("remote", "model-first", "key-first", "http://127.0.0.1:9/first/v1");
            first.installClient(new AnsweringClient("first"));
            second.configure("remote", "model-second", "key-second", "http://127.0.0.1:9/second/v1");
            second.installClient(new AnsweringClient("second"));

            var bridge = new ChannelAiBridge(List.of(), new ChannelFilterChain(List.of()), second);
            var callAiRaw = ChannelAiBridge.class.getDeclaredMethod("callAiRaw", String.class);
            callAiRaw.setAccessible(true);

            assertEquals("second:model-second", callAiRaw.invoke(bridge, "hello"),
                    "the bridge answers with its application's client and model");
        } finally {
            second.close();
            first.close();
        }
    }

    /** Answers with its name and the model it was asked for. */
    private record AnsweringClient(String name) implements LlmClient {

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
            session.send(name + ":" + request.model());
            session.complete();
        }
    }
}
