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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.approval.ApprovalRegistry;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@link AiPipeline} side of the intent-routing e2e: each prompt runs
 * through {@link AiPipeline#execute} with
 * {@link AiPipeline#setDefaultIntentRouting} set to
 * {@link ScriptedIntentRuntime#customerRouting}; the LLM route is an echo
 * runtime that answers {@code "llm: <message>"}. A {@code /__approval/<id>/...}
 * message resolves a pending CONFIRM-tier confirmation through
 * {@link AiPipeline#tryResolveApproval}. The {@code @AiEndpoint} side is
 * {@link IntentRoutingTestEndpoint}.
 */
public class IntentRoutingTestHandler implements AtmosphereHandler {

    private final AiPipeline pipeline = new AiPipeline(new EchoRuntime(), "You help customers.",
            null, null, null, List.of(), List.of(), AiMetrics.NOOP);

    public IntentRoutingTestHandler() {
        pipeline.setDefaultIntentRouting(ScriptedIntentRuntime.customerRouting(new AtomicInteger()));
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var prompt = resource.getRequest().getReader().readLine();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        var message = prompt.trim();
        if (ApprovalRegistry.isApprovalMessage(message)) {
            pipeline.tryResolveApproval(message);
            return;
        }
        Thread.ofVirtual().name("intent-routing-test").start(
                () -> pipeline.execute(resource.uuid(), message, StreamingSessions.start(resource)));
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        if (event.getMessage() instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
        // Nothing owned: the runtimes are in-memory.
    }

    /** The LLM route: answers with the message, so the spec sees the runtime ran. */
    private static final class EchoRuntime implements AgentRuntime {
        @Override public String name() { return "intent-echo"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send("llm: " + context.message());
            session.complete();
        }
    }
}
