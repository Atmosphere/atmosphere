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

import org.atmosphere.a2a.annotation.A2aParam;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.a2a.annotation.A2aSkill;
import org.atmosphere.a2a.annotation.A2aTaskHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent Strategy Agent — SWOT analysis and competitive positioning.
 * Discoverable at {@code /atmosphere/a2a/strategy/agent.json}.
 */
@Agent(
        name = "strategy-agent",
        description = "Market strategy agent that provides SWOT analysis, competitive positioning, and go/no-go recommendations",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/strategy"
)
public class StrategyAgent {

    private static final Logger logger = LoggerFactory.getLogger(StrategyAgent.class);

    @A2aSkill(id = "analyze_strategy", name = "Analyze Strategy",
            description = "Analyze market research data and identify strategic opportunities, threats, and positioning",
            tags = {"strategy", "swot", "analysis"})
    @A2aTaskHandler
    public void analyzeStrategy(TaskContext task,
                                @A2aParam(name = "market", description = "Market being analyzed") String market,
                                @A2aParam(name = "research_findings", description = "Key findings from research") String findings,
                                @A2aParam(name = "focus_area", description = "Strategic question") String focusArea) {
        task.updateStatus(TaskState.WORKING, "Analyzing strategy for: " + market);
        logger.info("Strategy Agent: analyzing {} market, focus: {}", market, focusArea);

        var analysis = String.format("""
                Strategic Analysis: %s
                Focus: %s

                MARKET CONTEXT:
                %s

                OPPORTUNITIES:
                - Market in growth phase with strong tailwinds (30%%+ CAGR)
                - Fragmented competitive landscape allows differentiated entry
                - Enterprise adoption accelerating, creating premium segment

                THREATS:
                - Well-funded incumbents with established distribution
                - Rapid technology evolution may commoditize current advantages
                - Talent competition for AI/ML engineering resources

                COMPETITIVE POSITIONING:
                - Differentiation via vertical specialization or unique UX
                - Open-source community strategy for developer adoption
                - Enterprise-first or developer-first go-to-market

                RECOMMENDATION:
                - Market timing: FAVORABLE (growth phase, not saturated)
                - Entry strategy: Niche-first, then expand
                - Key success factor: Ship fast, iterate with user feedback
                """, market, focusArea, findings.length() > 200 ? findings.substring(0, 200) + "..." : findings);

        task.addArtifact(Artifact.text(analysis));
        task.complete("Strategy analysis complete for " + market);
    }
}
