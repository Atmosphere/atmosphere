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
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Gap #5 — exercise the cache-safety gate formula lifted from
 * {@code AiPipeline#execute} (lines 243-250 in the worktree):
 * <pre>{@code
 * var hasTools          = context.tools() != null && !context.tools().isEmpty();
 * var registryHasTools  = toolRegistry != null && !toolRegistry.allTools().isEmpty();
 * var hasStructured     = effectiveResponseType != null && effectiveResponseType != Void.class;
 * var hasRag            = contextProviders != null && !contextProviders.isEmpty();
 * var hasGuardrails     = !guardrails.isEmpty();
 * var cacheSafe         = !hasTools && !registryHasTools && !hasStructured
 *                         && !hasRag && !hasGuardrails;
 * }</pre>
 *
 * <p><b>Why this is a white-box gate-calculation test, not an end-to-end
 * cache-serve test:</b> the current {@code AiPipeline.execute(String, String,
 * StreamingSession)} hard-codes {@code Map.of()} when building its
 * {@code AiRequest}, so {@link org.atmosphere.ai.llm.CacheHint#from} always
 * returns {@link org.atmosphere.ai.llm.CacheHint#none()} on the pipeline path
 * and the whole {@code cacheSafe} branch is unreachable via the public
 * {@code AiPipeline} API today. Because this handler may not touch
 * {@code modules/ai/**} to thread a cache hint into metadata, we recompute the
 * 5-gate formula inline using the exact same {@code AgentExecutionContext},
 * {@code DefaultToolRegistry}, guardrail list and context-provider list the
 * pipeline would hold, and publish each boolean as metadata so the spec can
 * assert that each toggle flips the expected gate.
 *
 * <p>If a future {@code AiPipeline} change lets callers plumb a
 * {@link org.atmosphere.ai.llm.CacheHint} into pipeline metadata, this handler
 * should be upgraded to a true end-to-end cache-hit vs cache-miss assertion;
 * until then the formula is pinned here so drift in the gate variables breaks
 * a Playwright spec, not just a unit test.</p>
 *
 * <p>Query params (from the WebSocket path suffix or message prompt): the
 * handler reads a single line of text and treats it as the toggle selector.
 * Accepted values: {@code none}, {@code tool}, {@code rag}, {@code guardrail},
 * {@code structured}. Unknown values map to {@code none}.</p>
 */
public class CacheSkipTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var reader = resource.getRequest().getReader();
        var line = reader.readLine();
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        var toggle = line.trim().toLowerCase();
        Thread.ofVirtual().name("cache-skip-test").start(() -> handle(toggle, resource));
    }

    private void handle(String toggle, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);

        var toolRegistry = new DefaultToolRegistry();
        List<AiGuardrail> guardrails = List.of();
        List<ContextProvider> contextProviders = List.of();
        Class<?> responseType = null;
        // Context.tools() is the request-scoped tool list (guardrail-narrowed),
        // distinct from the latent `toolRegistry` that AiPipeline also checks
        // even when the current request has no request-scoped tools.
        List<ToolDefinition> requestTools = List.of();

        switch (toggle) {
            case "tool" -> {
                // Populate the pipeline-scoped registry so AiPipeline's
                // `registryHasTools` gate fires even if the request-scoped
                // tool list happens to be empty.
                toolRegistry.register(ToolDefinition.builder("noop_tool", "no-op")
                        .returnType("string")
                        .executor(args -> "ok")
                        .build());
            }
            case "rag" -> contextProviders = List.of(new NoopContextProvider());
            case "guardrail" -> guardrails = List.of(new NoopGuardrail());
            case "structured" -> responseType = Map.class;
            case "none" -> { /* baseline: all gates false */ }
            default -> toggle = "none";
        }

        // Build the context the pipeline would have built for the current
        // request. Pipeline uses metadata=Map.of() on this code path, which is
        // precisely why cacheHint is always none() here — preserved intentionally.
        var context = new AgentExecutionContext(
                "same-prompt", null, "cache-skip-model",
                null, session.sessionId(), "user-1", "conv-1",
                requestTools, null, null,
                contextProviders, Map.of(), List.of(),
                responseType, null);

        // Mirror of AiPipeline.execute() lines 245-250. We read the gate
        // variables off the constructed context so AgentExecutionContext's
        // defensive copies (which the pipeline also observes) are in the
        // evaluation path.
        var hasTools = context.tools() != null && !context.tools().isEmpty();
        var registryHasTools = !toolRegistry.allTools().isEmpty();
        var hasStructured = context.responseType() != null && context.responseType() != Void.class;
        var hasRag = !context.contextProviders().isEmpty();
        var hasGuardrails = !guardrails.isEmpty();
        var cacheSafe = !hasTools && !registryHasTools && !hasStructured && !hasRag && !hasGuardrails;

        session.sendMetadata("cacheSkip.toggle", toggle);
        session.sendMetadata("cacheSkip.hasTools", hasTools);
        session.sendMetadata("cacheSkip.registryHasTools", registryHasTools);
        session.sendMetadata("cacheSkip.hasStructured", hasStructured);
        session.sendMetadata("cacheSkip.hasRag", hasRag);
        session.sendMetadata("cacheSkip.hasGuardrails", hasGuardrails);
        session.sendMetadata("cacheSkip.cacheSafe", cacheSafe);
        session.complete();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }

    private static final class NoopContextProvider implements ContextProvider {
        @Override
        public List<Document> retrieve(String query, int maxResults) {
            return List.of();
        }
    }

    private static final class NoopGuardrail implements AiGuardrail {
        // Inherits default pass() behaviour; its mere presence in the list
        // is what flips the `hasGuardrails` gate.
    }
}
