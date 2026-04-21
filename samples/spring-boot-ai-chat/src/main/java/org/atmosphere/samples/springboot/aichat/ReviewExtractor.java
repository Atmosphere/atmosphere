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
package org.atmosphere.samples.springboot.aichat;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured output endpoint that extracts a {@link MovieReview} from
 * free-text user input. The framework automatically appends JSON schema
 * instructions to the system prompt, streams text normally, and emits
 * {@code EntityStart}, {@code StructuredField}, and {@code EntityComplete}
 * events with the parsed result.
 *
 * <p>Try: "Review the movie Inception - it was mind-bending, I'd give it 9/10"</p>
 */
@AiEndpoint(path = "/atmosphere/review-extractor",
        systemPrompt = "You are a movie review analyst. Extract structured reviews from user input. Respond with valid JSON only.",
        responseAs = MovieReview.class,
        conversationMemory = false)
@AgentScope(purpose = "Extract structured movie review data from a user-supplied review text.")
public class ReviewExtractor {

    private static final Logger logger = LoggerFactory.getLogger(ReviewExtractor.class);

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Extracting review from: {}", message);
        session.stream(message);
    }
}
