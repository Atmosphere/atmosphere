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
package org.atmosphere.integrationtests.ai.structured;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;

/**
 * Test endpoint for structured output E2E. Simulates an LLM that returns
 * JSON conforming to {@link FilmReview}. The framework wraps the session
 * with {@code StructuredOutputCapturingSession} which parses the output
 * and emits entity events.
 */
@AiEndpoint(path = "/atmosphere/structured-test",
        systemPrompt = "Return JSON only.",
        responseAs = FilmReview.class)
public class StructuredOutputTestEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        // Simulate an LLM that returns valid JSON for a film review
        session.send("{\"title\": \"Inception\", ");
        session.send("\"rating\": 9, ");
        session.send("\"summary\": \"A mind-bending thriller\"}");
        session.complete();
    }
}
