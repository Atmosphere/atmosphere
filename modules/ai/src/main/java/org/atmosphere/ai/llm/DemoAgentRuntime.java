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
package org.atmosphere.ai.llm;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Fallback runtime that streams a canned "no API key configured" response
 * through the standard pipeline.
 *
 * <p>Runs when {@link AiConfig#get()} has no API key, so samples work
 * out-of-the-box without an {@code LLM_API_KEY}. It wins over every real
 * runtime only in that no-key state (via {@link #isAvailable()}); when a key
 * is configured the runtime is invisible to the resolver and
 * LangChain4j / Spring AI / the Built-in runtime take over as usual.</p>
 *
 * <p>Crucially this goes through the same {@code AiStreamingSession} pipeline
 * as every other runtime — guardrails, interceptors, policies, memory, and
 * metrics all fire the same way, so the browser renders the response via the
 * standard {@code streaming-text}/{@code complete} frames. Before this class
 * existed, each sample short-circuited {@code @Prompt} handlers into a
 * {@code DemoResponseProducer.stream(session, …)} call that bypassed the
 * pipeline — a structural mismatch that let policy gaps and frame-shape
 * regressions slip through in demo mode.</p>
 *
 * <p>Samples may customise the demo text by calling
 * {@link #setResponseStrategy(Function)} at startup. The default strategy
 * echoes back the user's prompt and explains how to enable real responses.</p>
 */
public final class DemoAgentRuntime implements AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(DemoAgentRuntime.class);

    /** Per-word delay for the simulated streaming cadence. */
    private static final long WORD_DELAY_MS = 30L;

    private static final AtomicReference<Function<AgentExecutionContext, String>> STRATEGY =
            new AtomicReference<>(DemoAgentRuntime::defaultResponse);

    /**
     * Override the response strategy. Samples with a richer demo experience
     * (e.g. room-aware tutor personas) can install their own strategy at
     * startup. Pass {@code null} to reset to the built-in default.
     */
    public static void setResponseStrategy(Function<AgentExecutionContext, String> strategy) {
        STRATEGY.set(strategy != null ? strategy : DemoAgentRuntime::defaultResponse);
    }

    /** Return the strategy currently in effect — visible for tests. */
    static Function<AgentExecutionContext, String> currentStrategy() {
        return STRATEGY.get();
    }

    @Override
    public String name() {
        return "demo";
    }

    /**
     * Available only when no API key is configured. Real runtimes remain
     * the resolver's first choice the moment a key is present.
     */
    @Override
    public boolean isAvailable() {
        var cfg = AiConfig.get();
        return cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank();
    }

    /**
     * Highest possible priority so that when the runtime is available
     * (no-key state) it wins against every real runtime on the classpath.
     */
    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // No native client; settings are only used for logging.
        logger.info("Demo runtime active (no API key configured — set LLM_API_KEY to enable real responses)");
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT);
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        var response = STRATEGY.get().apply(context);
        try {
            session.progress("Demo mode — set LLM_API_KEY to enable real AI responses");
            for (var word : response.split("(?<=\\s)")) {
                session.send(word);
                Thread.sleep(WORD_DELAY_MS);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static String defaultResponse(AgentExecutionContext context) {
        var echoed = context.message() != null && !context.message().isBlank()
                ? "You said: “" + context.message().strip() + "”\n\n"
                : "";
        return echoed
                + "**Demo mode** — this response is a canned placeholder because no "
                + "`LLM_API_KEY` is configured. Export a Gemini, OpenAI, or Ollama key "
                + "(see the sample README) and restart to get a real AI reply.";
    }
}
