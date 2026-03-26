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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * CEO Coordinator — manages a fleet of specialist agents via the
 * {@code @Coordinator} abstraction. Replaces the 277-line manual HTTP/JSON-RPC
 * implementation with ~50 lines of pure orchestration logic.
 *
 * <p>The fleet delegates to 4 specialist agents:</p>
 * <ul>
 *   <li><b>research</b> — web scraping + search</li>
 *   <li><b>strategy</b> — SWOT analysis and positioning</li>
 *   <li><b>finance</b> — financial modeling and projections</li>
 *   <li><b>writer</b> — report generation</li>
 * </ul>
 */
@Coordinator(name = "ceo",
        skillFile = "prompts/ceo-skill.md",
        description = "Startup CEO that coordinates specialist A2A agents for market analysis")
@Fleet({
        @AgentRef(type = ResearchAgent.class),
        @AgentRef(type = StrategyAgent.class),
        @AgentRef(type = FinanceAgent.class),
        @AgentRef(type = WriterAgent.class)
})
public class CeoCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(CeoCoordinator.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected to CEO", resource.uuid());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        logger.info("CEO received: {}", message);

        var settings = AiConfig.get();
        if (settings == null || settings.client() == null
                || settings.client().apiKey() == null
                || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        // Step 1: Research (sequential — need results before other agents)
        var researchArgs = Map.of("query", message, "num_results", "3");
        session.emit(new AiEvent.ToolStart("web_search", Map.<String, Object>of("query", message)));
        var research = fleet.agent("research-agent").call("web_search", researchArgs);
        session.emit(new AiEvent.ToolResult("web_search", research.text()));

        // Step 2: Strategy + Finance in parallel
        var strategyArgs = Map.of("market", message,
                "research_findings", research.text(), "focus_area", "market entry");
        var financeArgs = Map.of("market", message,
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

        // Step 3: Writer synthesizes findings
        var writerArgs = Map.of("title", message + " — Market Analysis",
                "key_findings", research.text() + "\n"
                        + results.get("strategy-agent").text(),
                "recommendation", "Comprehensive analysis with financial projections");
        session.emit(new AiEvent.ToolStart("write_report",
                Map.<String, Object>of("title", message)));
        var report = fleet.agent("writer-agent").call("write_report", writerArgs);
        session.emit(new AiEvent.ToolResult("write_report", report.text()));

        // Step 4: CEO synthesis via LLM
        session.stream(String.format(
                "You are a startup CEO synthesizing findings from your team. "
                + "Each agent worked independently via the A2A protocol. Write a concise "
                + "executive briefing: 1) Executive Summary (3 bullets), "
                + "2) GO/NO-GO recommendation, 3) Key risks, 4) Next steps.\n\n"
                + "RESEARCH AGENT:\n%s\n\nSTRATEGY AGENT:\n%s\n\nFINANCE AGENT:\n%s\n\n"
                + "Be concise and decisive. Use markdown.",
                research.text(),
                results.get("strategy-agent").text(),
                results.get("finance-agent").text()));
    }
}
