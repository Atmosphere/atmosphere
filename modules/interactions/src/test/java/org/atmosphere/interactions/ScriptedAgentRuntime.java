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
package org.atmosphere.interactions;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Test {@link AgentRuntime} that drives a caller-supplied script of session
 * calls, so tests can assert exactly how the capturing session and service treat
 * a deterministic event sequence. Records the last {@link AgentExecutionContext}
 * it received for chaining/history assertions.
 */
final class ScriptedAgentRuntime implements AgentRuntime {

    private final Consumer<StreamingSession> script;
    private final AtomicReference<AgentExecutionContext> lastContext = new AtomicReference<>();

    ScriptedAgentRuntime(Consumer<StreamingSession> script) {
        this.script = script;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // no-op
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        lastContext.set(context);
        script.accept(session);
    }

    AgentExecutionContext lastContext() {
        return lastContext.get();
    }
}
