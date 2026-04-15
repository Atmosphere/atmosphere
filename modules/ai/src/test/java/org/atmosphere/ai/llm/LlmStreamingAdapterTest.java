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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AiStreamingAdapter;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LlmStreamingAdapterTest {

    @Test
    void nameReturnsOpenAiCompatible() {
        var client = mock(LlmClient.class);
        var adapter = new LlmStreamingAdapter(client);
        assertEquals("openai-compatible", adapter.name());
    }

    @Test
    void streamDelegatesToClient() {
        var client = mock(LlmClient.class);
        var session = mock(StreamingSession.class);
        var request = mock(ChatCompletionRequest.class);

        var adapter = new LlmStreamingAdapter(client);
        adapter.stream(request, session);

        verify(client).streamChatCompletion(request, session);
    }

    @Test
    void implementsAiStreamingAdapter() {
        var client = mock(LlmClient.class);
        var adapter = new LlmStreamingAdapter(client);
        assertInstanceOf(AiStreamingAdapter.class, adapter);
    }
}
