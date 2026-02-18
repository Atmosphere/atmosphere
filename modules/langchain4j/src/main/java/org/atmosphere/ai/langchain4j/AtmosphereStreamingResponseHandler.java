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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.atmosphere.ai.StreamingSession;

/**
 * LangChain4j streaming response handler that forwards tokens to an
 * Atmosphere {@link StreamingSession}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * var session = StreamingSessions.start(resource);
 * var handler = new AtmosphereStreamingResponseHandler(session);
 * model.chat(ChatRequest.builder().messages(messages).build(), handler);
 * }</pre>
 */
public class AtmosphereStreamingResponseHandler implements StreamingChatResponseHandler {

    private final StreamingSession session;

    public AtmosphereStreamingResponseHandler(StreamingSession session) {
        this.session = session;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        session.send(partialResponse);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        AiMessage aiMessage = completeResponse.aiMessage();
        if (aiMessage != null && aiMessage.text() != null) {
            session.complete(aiMessage.text());
        } else {
            session.complete();
        }
    }

    @Override
    public void onError(Throwable error) {
        session.error(error);
    }
}
