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
package org.atmosphere.samples.springboot.onedepagent;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.Prompt;

/**
 * The whole application logic: one {@code @Agent} class.
 *
 * <p>{@code @Agent} makes the {@code AgentProcessor} register a streaming web
 * handler at {@code /atmosphere/agent/chat}. The {@code @Prompt} method receives
 * each user message and calls {@link StreamingSession#stream(String)}, which
 * routes through the standard AI pipeline to the highest-priority
 * {@code AgentRuntime} on the classpath. With no {@code LLM_API_KEY} that is the
 * framework's built-in demo runtime, which streams a response token-by-token as
 * {@code {"type":"streaming-text",...}} frames followed by a {@code complete}
 * frame — exactly the wire shape the Atmosphere Console renders. Configure a key
 * and the same one line streams from a real provider instead; no other change.</p>
 *
 * <p>{@code atmosphere-agent}, {@code atmosphere-ai} and {@link StreamingSession}
 * reach this module only transitively through the single
 * {@code atmosphere-ai-spring-boot-starter} dependency in {@code pom.xml}.</p>
 */
@Agent(name = "chat", description = "One-dependency streaming chat agent")
@AgentScope(unrestricted = true,
        justification = "Minimal one-dependency demo: a general-purpose chat agent with no "
                + "restricted domain. A real app should replace this with a scoped @AgentScope "
                + "declaring purpose + forbiddenTopics.")
public class ChatAgent {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
