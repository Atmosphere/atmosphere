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

import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent Finance Agent — TAM/SAM/SOM modeling and revenue projections.
 * Discoverable at {@code /atmosphere/a2a/finance/agent.json}.
 */
@Agent(
        name = "finance-agent",
        skillFile = "skill:finance-agent",
        description = "Financial modeling agent that builds TAM/SAM/SOM analysis, revenue projections, and funding requirements",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/finance"
)
public class FinanceAgent {

    private static final Logger logger = LoggerFactory.getLogger(FinanceAgent.class);

    @AgentSkill(id = "financial_model", name = "Financial Model",
            description = "Build TAM/SAM/SOM analysis, revenue projections, burn rate, and funding requirements",
            tags = {"finance", "projections", "tam"})
    @AgentSkillHandler
    public void financialModel(TaskContext task,
                               @AgentSkillParam(name = "market", description = "Target market") String market,
                               @AgentSkillParam(name = "tam_estimate", description = "TAM in billions USD") String tamEstimate,
                               @AgentSkillParam(name = "growth_rate", description = "Annual growth rate %") String growthRate,
                               @AgentSkillParam(name = "pricing_model", description = "Pricing approach") String pricingModel) {
        task.updateStatus(TaskState.WORKING, "Building financial model for: " + market);
        logger.info("Finance Agent: modeling {} (TAM: ${}B, growth: {}%)", market, tamEstimate, growthRate);

        double tam;
        double growth;
        try {
            tam = Double.parseDouble(tamEstimate);
            growth = Double.parseDouble(growthRate) / 100.0;
        } catch (NumberFormatException e) {
            tam = 10.0;
            growth = 0.30;
        }

        double sam = tam * 0.20;
        double som = sam * 0.05;
        double y1 = som * 0.01 * 1_000_000_000;
        double y2 = y1 * (1 + growth) * 2.5;
        double y3 = y2 * (1 + growth) * 1.8;
        double burn = 85_000;

        var model = String.format("""
                Financial Model: %s
                Pricing: %s

                MARKET SIZING:
                  TAM: $%.1fB
                  SAM: $%.1fB (%.0f%% of TAM)
                  SOM: $%.1fB (%.0f%% of SAM)

                REVENUE PROJECTIONS:
                  Year 1: $%,.0f (market entry)
                  Year 2: $%,.0f (growth phase)
                  Year 3: $%,.0f (scale phase)

                COST STRUCTURE:
                  Monthly burn: $%,.0f
                  Team (5 eng): $50,000/mo
                  Infra: $15,000/mo
                  Marketing: $12,000/mo

                FUNDING:
                  Seed round: $%,.0f (18mo runway)
                  Break-even: Month 14-16
                  Recommended: $2M at $8M pre-money
                """, market, pricingModel,
                tam, sam, (sam / tam) * 100, som, (som / sam) * 100,
                y1, y2, y3, burn, burn * 18);

        task.addArtifact(Artifact.text(model));
        task.complete("Financial model complete");
    }
}
