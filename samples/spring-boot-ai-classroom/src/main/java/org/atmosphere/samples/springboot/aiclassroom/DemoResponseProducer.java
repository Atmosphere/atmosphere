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
 * <p>The {@link AgentExecutionContext#systemPrompt()} carries the room's
 * persona (installed by {@code RoomContextInterceptor}), so the strategy
 * looks for a lowercase room keyword anywhere in the system prompt to pick
 * the right flavour of canned response.</p>
 */
@Component
public class DemoResponseProducer {

    @PostConstruct
    public void installRoomAwareStrategy() {
        DemoAgentRuntime.setResponseStrategy(DemoResponseProducer::generateFor);
    }

    private static String generateFor(AgentExecutionContext context) {
        var system = context.systemPrompt() == null ? "" : context.systemPrompt().toLowerCase();
        if (system.contains("math")) {
            return "**Math Tutor**: Great question! Mathematics is all about "
                    + "patterns and logical reasoning. In demo mode, I can't compute "
                    + "real answers, but with an API key I'll solve equations step by step.\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
        }
        if (system.contains("code")) {
            return "**Code Mentor**: Good software is built on clear abstractions "
                    + "and clean design. In demo mode, I can't write real code, but with "
                    + "an API key I'll provide working examples with explanations.\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
        }
        if (system.contains("science")) {
            return "**Science Educator**: Science is the art of asking why and "
                    + "testing how. In demo mode, I can't give detailed explanations, but "
                    + "with an API key I'll break down complex topics with analogies.\n\n"
                    + "Set `LLM_API_KEY` to connect to a real AI model.";
        }
        return "**Classroom AI**: Every student in this room is seeing this "
                + "response stream in real time — that's Atmosphere's broadcaster "
                + "at work!\n\n"
                + "Set `LLM_API_KEY` to connect to a real AI model.";
    }
}
