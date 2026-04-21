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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.StreamingActivityListener;
import org.atmosphere.coordinator.journal.JournalFormat;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * CEO Coordinator — the orchestrator of a multi-agent startup advisory team.
 *
 * <h2>What this class demonstrates</h2>
 * <ul>
 *   <li>{@code @Coordinator} — registers this class as a fleet manager with a
 *       WebSocket endpoint at {@code /atmosphere/agent/ceo}</li>
 *   <li>{@code @Fleet} — declares the 4 specialist agents this coordinator manages</li>
 *   <li>{@code AgentFleet} — injected into {@code @Prompt}, provides
 *       {@code agent().call()} for sequential and {@code parallel()} for concurrent dispatch</li>
 *   <li>{@code CoordinationJournal} — records every dispatch/completion event for observability</li>
 *   <li>{@code session.emit(AiEvent.ToolStart/ToolResult)} — renders agent results as
 *       expandable tool cards in the Atmosphere AI Console</li>
 *   <li>{@code session.stream()} — delegates to the active {@code AgentRuntime}
 *       (ADK, Embabel, Spring AI, or LangChain4j) for the final LLM synthesis</li>
 * </ul>
 *
 * <h2>Execution flow</h2>
 * <pre>
 *   User message (WebSocket)
 *     |
 *     v
 *   Step 1: Research Agent (sequential — web scraping via JSoup)
 *     |
 *   Step 2: Strategy + Finance agents (parallel — both use research results)
 *     |
 *   Step 3: Writer Agent (sequential — synthesizes all findings)
 *     |
 *   Step 4: Coordination Journal (query all events, display in console)
 *     |
 *   Step 5: CEO LLM synthesis (stream executive briefing to browser)
 * </pre>
 *
 * <h2>How specialist agents are discovered</h2>
 * <p>The {@code @Fleet} annotation lists agent classes. At startup, the
 * {@code CoordinatorProcessor} resolves each class to its {@code @Agent} name
 * and transport (local or A2A). Local agents are invoked directly in-JVM;
 * remote agents use A2A JSON-RPC over HTTP.</p>
 *
 * <h2>Skill files</h2>
 * <p>The {@code skillFile} attribute points to a Markdown file in
 * {@code src/main/resources/prompts/} that defines the agent's persona,
 * capabilities ({@code ## Skills}), and boundaries ({@code ## Guardrails}).
 * This file becomes the system prompt for the coordinator's LLM calls.</p>
 */
@Coordinator(name = "ceo",
        skillFile = "skill:startup-ceo",
        description = "Startup CEO that coordinates specialist A2A agents for market analysis",
        // Uncomment to get structured JSON output parsed into MarketAssessment fields:
        // responseAs = MarketAssessment.class,
        journalFormat = JournalFormat.Markdown.class)
@Fleet({
        @AgentRef(type = ResearchAgent.class),
        @AgentRef(type = StrategyAgent.class),
        @AgentRef(type = FinanceAgent.class),
        @AgentRef(type = WriterAgent.class)
})
public class CeoCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(CeoCoordinator.class);

    /** Called when a browser client connects via WebSocket. */
    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected to CEO", resource.uuid());
    }

    /** Called when a browser client disconnects. */
    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    /**
     * Truncates text to avoid exceeding LLM context limits.
     * Agent results can be large; the CEO only needs summaries for synthesis.
     */
    private static String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen
                ? text.substring(0, maxLen) + "..." : (text != null ? text : "");
    }

    /**
     * Main orchestration method — called when a user sends a message via WebSocket.
     *
     * <p>The three parameters are injected by Atmosphere's type-based resolution:</p>
     * <ul>
     *   <li>{@code message} — the user's raw text from the WebSocket frame</li>
     *   <li>{@code fleet} — the {@link AgentFleet} wired from the {@code @Fleet}
     *       annotation, pre-configured with transport for each agent</li>
     *   <li>{@code session} — the {@link StreamingSession} for sending real-time
     *       events (tool cards, progress, streaming text) back to the browser</li>
     * </ul>
     *
     * @param message the user prompt (e.g., "Analyze the market for AI fitness apps")
     * @param fleet   the agent fleet with 4 specialist agents
     * @param session the streaming session for real-time browser updates
     */
    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        logger.info("CEO received: {}", message);

        // Wire per-session activity streaming — clients see agent-step events in real time
        fleet = fleet.withActivityListener(new StreamingActivityListener(session));

        // --- Step 1: Research (sequential — other agents need these results) ---
        // ToolStart/ToolResult events render as expandable cards in the console.
        var researchArgs = Map.<String, Object>of("query", message, "num_results", "3");
        session.emit(new AiEvent.ToolStart("web_search", Map.<String, Object>of("query", message)));
        var research = fleet.agent("research-agent").call("web_search", researchArgs);
        session.emit(new AiEvent.ToolResult("web_search", research.text()));

        // --- Step 2: Strategy + Finance in parallel ---
        // fleet.parallel() dispatches both agents concurrently and blocks until
        // both complete. Results are keyed by agent name.
        var strategyArgs = Map.<String, Object>of("market", message,
                "research_findings", research.text(), "focus_area", "market entry");
        var financeArgs = Map.<String, Object>of("market", message,
                "tam_estimate", "15", "growth_rate", "35",
                "pricing_model", "SaaS $29/mo per seat");
        session.emit(new AiEvent.ToolStart("analyze_strategy",
                Map.<String, Object>of("market", message)));
        session.emit(new AiEvent.ToolStart("financial_model",
                Map.<String, Object>of("market", message)));
        var results = fleet.parallel(
                fleet.call("strategy-agent", "analyze_strategy", strategyArgs),
                fleet.call("finance-agent", "financial_model", financeArgs)
        );
        session.emit(new AiEvent.ToolResult("analyze_strategy",
                results.get("strategy-agent").text()));
        session.emit(new AiEvent.ToolResult("financial_model",
                results.get("finance-agent").text()));

        // --- Step 3: Writer synthesizes all findings into a report ---
        var writerArgs = Map.<String, Object>of("title", message + " — Market Analysis",
                "key_findings", research.text() + "\n"
                        + results.get("strategy-agent").text(),
                "recommendation", "Comprehensive analysis with financial projections");
        session.emit(new AiEvent.ToolStart("write_report",
                Map.<String, Object>of("title", message)));
        var report = fleet.agent("writer-agent").call("write_report", writerArgs);
        session.emit(new AiEvent.ToolResult("write_report", report.text()));

        // --- Step 3b: Conditional routing — choose synthesis path based on finance result ---
        // fleet.route() evaluates conditions in order, first match wins.
        // Every routing decision is recorded in the CoordinationJournal.
        var synthesisInput = fleet.route(results.get("finance-agent"), route -> route
                .when(r -> r.success() && r.text().contains("GO"),
                        f -> f.agent("writer-agent").call("write_report", writerArgs))
                .when(r -> r.success(),
                        f -> f.agent("writer-agent").call("write_report",
                                Map.<String, Object>of("title", message + " — Risk Assessment",
                                        "key_findings", research.text(),
                                        "recommendation", "Conservative analysis")))
                .otherwise(f -> report)
        );
        var finalReport = synthesisInput.success() ? synthesisInput : report;
        session.emit(new AiEvent.ToolResult("write_report", finalReport.text()));

        // --- Step 3c: Result evaluation (when configured) ---
        // fleet.evaluate(result, call) runs ResultEvaluator SPIs (async, non-blocking, journaled).
        // This demonstrates the API — production use would provide a real evaluator via ServiceLoader.
        logger.info("Report ready for evaluation: {} chars", finalReport.text().length());

        // Journal is auto-emitted by the framework via journalFormat = Markdown.class
        // (PostPromptHook fires after @Prompt returns, before async LLM streaming completes)

        // --- Step 4: CEO synthesis via LLM ---
        // Trim agent results to fit within the LLM context window, then stream
        // the executive briefing to the browser in real-time via session.stream().
        // Delegates to whichever AgentRuntime the resolver picks — real (ADK,
        // Embabel, Spring AI, LangChain4j) when LLM_API_KEY is set, or the
        // built-in DemoAgentRuntime when not.
        var researchSummary = truncate(research.text(), 800);
        var strategySummary = truncate(results.get("strategy-agent").text(), 800);
        var financeSummary = truncate(results.get("finance-agent").text(), 800);

        session.stream(String.format(
                "Based on team findings, write an executive briefing. Include: "
                + "Executive Summary (3 bullets), GO/NO-GO recommendation with rationale, "
                + "4 key risks, and 4 next steps. Research: %s Strategy: %s Finance: %s",
                researchSummary, strategySummary, financeSummary));
    }
}
