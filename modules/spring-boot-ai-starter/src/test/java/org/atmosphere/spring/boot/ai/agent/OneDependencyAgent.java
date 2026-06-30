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
package org.atmosphere.spring.boot.ai.agent;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;

/**
 * The single {@code @Agent} class under test. It imports
 * {@code org.atmosphere.agent.annotation.Agent} and
 * {@code org.atmosphere.ai.StreamingSession} — types that reach this module
 * <em>only transitively</em> through {@code atmosphere-ai-spring-boot-starter}.
 * If the starter declared {@code atmosphere-agent} / {@code atmosphere-ai} the
 * way the base starter does (optional), this class would still compile in-module
 * but a downstream consumer would not get them; the starter pins both
 * non-optionally so the one-dependency promise holds end to end.
 *
 * <p>Lives in a dedicated package so {@code atmosphere.packages} can point the
 * framework's annotation scanner straight at it, and so Spring component
 * scanning never mistakes it for a bean (it is instantiated by the Atmosphere
 * object factory, not the application context).</p>
 */
@Agent(name = "oneDepAgent",
        description = "Proves a single @Agent runs from the atmosphere-ai-spring-boot-starter dependency alone")
public class OneDependencyAgent {

    /** Non-headless marker: a {@code @Prompt} method makes the processor register the web handler. */
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
