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
package org.atmosphere.samples.springboot.aiclassroom;

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.springframework.stereotype.Component;

/**
 * Installs a room-aware demo strategy so the bundled
 * {@link DemoAgentRuntime} streams math-tutor / code-mentor / science-educator
 * personas instead of its generic echo when the sample boots without an
 * {@code LLM_API_KEY}. Runs at startup; the framework then drives every
 * demo-mode request through the standard pipeline like any real runtime.
 *
 * <p>The {@link AgentExecutionContext#systemPrompt()} ends with the room's
 * persona (installed by {@code RoomContextInterceptor}), after the framework's
 * scope preamble. The strategy resolves the room from that persona with
 * {@link RoomContextInterceptor#roomOfSystemPrompt(String)}; a keyword search
 * over the whole prompt also matched the preamble, whose forbidden topics name
 * "source code" in the science room, and answered it as the Code Mentor.</p>
 */
@Component
public class DemoResponseProducer {

    @PostConstruct
    public void installRoomAwareStrategy() {
        DemoAgentRuntime.setResponseStrategy(DemoResponseProducer::generateFor);
    }

    static String generateFor(AgentExecutionContext context) {
        return switch (RoomContextInterceptor.roomOfSystemPrompt(context.systemPrompt()).orElse("")) {
            case "math" -> "**Math Tutor**: Great question! Mathematics is all about "
                    + "patterns and logical reasoning. In demo mode, I can't compute "
                    + "real answers, but with an API key I'll solve equations step by step.\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
            case "code" -> "**Code Mentor**: Good software is built on clear abstractions "
                    + "and clean design. In demo mode, I can't write real code, but with "
                    + "an API key I'll provide working examples with explanations.\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
            case "science" -> "**Science Educator**: Science is the art of asking why and "
                    + "testing how. In demo mode, I can't give detailed explanations, but "
                    + "with an API key I'll break down complex topics with analogies.\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
            default -> "**Classroom AI**: Every student in this room is seeing this "
                    + "response stream in real time — that's Atmosphere's broadcaster "
                    + "at work!\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
        };
    }
}
