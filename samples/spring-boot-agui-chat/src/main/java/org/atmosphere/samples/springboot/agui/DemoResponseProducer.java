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
package org.atmosphere.samples.springboot.agui;

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.springframework.stereotype.Component;

/**
 * Installs an AG-UI-flavoured demo strategy so the bundled
 * {@link DemoAgentRuntime} streams this sample's canned responses instead of
 * its generic echo when the sample boots without an {@code LLM_API_KEY}. Runs
 * at startup; the framework then drives every demo-mode request through the
 * standard pipeline like any real runtime — guardrails, interceptors, memory,
 * metrics, and the AG-UI mapper (TEXT_MESSAGE_START → N × TEXT_MESSAGE_CONTENT
 * → TEXT_MESSAGE_END → RUN_FINISHED) all fire the same way.
 *
 * <p>Set {@code LLM_API_KEY} to drive a real model instead (which additionally
 * exercises {@code @AiTool} dispatch).</p>
 */
@Component
public class DemoResponseProducer {

    /** Stable phrase the demo always includes, asserted by the e2e demo lane. */
    static final String DEMO_PHRASE = "AG-UI protocol";

    @PostConstruct
    public void installDemoStrategy() {
        DemoAgentRuntime.setResponseStrategy(DemoResponseProducer::generateFor);
    }

    static String generateFor(AgentExecutionContext context) {
        var lower = context.message() == null ? "" : context.message().toLowerCase();
        if (lower.contains("weather")) {
            return "I can fetch weather through the get_weather tool when a model is connected. "
                    + "Right now I'm streaming over the AG-UI protocol in demo mode — "
                    + "set LLM_API_KEY to let the model call the tool for real.";
        }
        if (lower.contains("time")) {
            return "I can look up the time through the get_time tool when a model is connected. "
                    + "Right now I'm streaming over the AG-UI protocol in demo mode — "
                    + "set LLM_API_KEY to let the model call the tool for real.";
        }
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm an Atmosphere agent streaming over the AG-UI protocol. "
                    + "I'm in demo mode because no LLM_API_KEY is set. Configure a key to get "
                    + "real model responses with get_weather and get_time tool calls.";
        }
        return "I received your message and streamed this reply over the AG-UI protocol. "
                + "This is demo mode (no LLM_API_KEY) — each word arrives as a separate "
                + "TEXT_MESSAGE_CONTENT frame. Set LLM_API_KEY for real model responses.";
    }
}
