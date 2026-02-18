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
package org.atmosphere.ai.langchain4j;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.atmosphere.ai.AiStreamingAdapter;
import org.atmosphere.ai.StreamingSession;

/**
 * LangChain4j adapter that bridges {@link StreamingChatLanguageModel}
 * to an Atmosphere {@link StreamingSession}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * var session = StreamingSessions.start(resource);
 * adapter.stream(new LangChain4jRequest(model, chatRequest), session);
 * }</pre>
 */
public class LangChain4jStreamingAdapter implements AiStreamingAdapter<LangChain4jStreamingAdapter.LangChain4jRequest> {

    @Override
    public String name() {
        return "langchain4j";
    }

    @Override
    public void stream(LangChain4jRequest request, StreamingSession session) {
        session.progress("Connecting to AI model...");
        var handler = new AtmosphereStreamingResponseHandler(session);
        request.model().chat(request.chatRequest(), handler);
    }

    /**
     * Convenience method for direct streaming.
     */
    public void stream(StreamingChatLanguageModel model, ChatRequest chatRequest, StreamingSession session) {
        stream(new LangChain4jRequest(model, chatRequest), session);
    }

    /**
     * Request record wrapping a model and chat request.
     */
    public record LangChain4jRequest(StreamingChatLanguageModel model, ChatRequest chatRequest) {
    }
}
