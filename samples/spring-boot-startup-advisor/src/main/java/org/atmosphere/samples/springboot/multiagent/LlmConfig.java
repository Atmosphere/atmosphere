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
package org.atmosphere.samples.springboot.multiagent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.adk.AdkAiSupport;
import org.atmosphere.ai.adk.AdkToolBridge;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Bridges Spring properties into Atmosphere's {@link AiConfig} and
 * reconfigures the ADK runner with tool definitions for multi-agent
 * tool calling support.
 */
@Configuration
public class LlmConfig {

    private static final Logger logger = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public AiConfig.LlmSettings llmSettings(
            @Value("${llm.mode:remote}") String mode,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:gemini-2.5-flash}") String model) {

        return AiConfig.configure(mode, model, apiKey, baseUrl.isBlank() ? null : baseUrl);
    }

    /**
     * ADK requires tools at agent construction time. This runner
     * reconfigures the ADK agent with our tool definitions after
     * Spring context is fully initialized.
     */
    @Bean
    public ApplicationRunner adkToolConfigurer(AiConfig.LlmSettings settings) {
        return args -> {
            if (settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
                return;
            }

            var tools = List.of(
                    ToolDefinition.builder("web_search",
                                    "Search the web for market data, news, competitors, and trends. "
                                            + "Scrapes real websites and returns relevant excerpts.")
                            .parameter("query", "Search query", "string")
                            .parameter("num_results", "Number of results (1-5)", "string", false)
                            .executor(a -> {
                                var agent = new CeoAgent();
                                return agent.webSearch(
                                        String.valueOf(a.getOrDefault("query", "")),
                                        String.valueOf(a.getOrDefault("num_results", "3")));
                            }).build(),
                    ToolDefinition.builder("analyze_strategy",
                                    "Analyze market research data and identify strategic opportunities, "
                                            + "threats, competitive advantages, and market positioning.")
                            .parameter("market", "Market or industry being analyzed", "string")
                            .parameter("research_findings", "Key findings from web research", "string")
                            .parameter("focus_area", "Specific strategic question", "string")
                            .executor(a -> {
                                var agent = new CeoAgent();
                                return agent.analyzeStrategy(
                                        String.valueOf(a.getOrDefault("market", "")),
                                        String.valueOf(a.getOrDefault("research_findings", "")),
                                        String.valueOf(a.getOrDefault("focus_area", "")));
                            }).build(),
                    ToolDefinition.builder("financial_model",
                                    "Build financial projections including TAM/SAM/SOM analysis, "
                                            + "revenue estimates, burn rate, and funding requirements.")
                            .parameter("market", "Target market", "string")
                            .parameter("tam_estimate", "Total addressable market in billions USD", "string")
                            .parameter("growth_rate", "Annual growth rate percentage", "string")
                            .parameter("pricing_model", "Pricing approach", "string")
                            .executor(a -> {
                                var agent = new CeoAgent();
                                return agent.financialModel(
                                        String.valueOf(a.getOrDefault("market", "")),
                                        String.valueOf(a.getOrDefault("tam_estimate", "10")),
                                        String.valueOf(a.getOrDefault("growth_rate", "30")),
                                        String.valueOf(a.getOrDefault("pricing_model", "SaaS")));
                            }).build(),
                    ToolDefinition.builder("write_report",
                                    "Synthesize research, strategy, and financial projections into "
                                            + "a polished executive briefing.")
                            .parameter("title", "Report title", "string")
                            .parameter("key_findings", "Summary of key findings", "string")
                            .parameter("recommendation", "Strategic recommendation", "string")
                            .executor(a -> {
                                var agent = new CeoAgent();
                                return agent.writeReport(
                                        String.valueOf(a.getOrDefault("title", "")),
                                        String.valueOf(a.getOrDefault("key_findings", "")),
                                        String.valueOf(a.getOrDefault("recommendation", "")));
                            }).build()
            );

            // Build ADK runner with the CEO skill instruction + tools
            var gemini = new Gemini(settings.model(), settings.client().apiKey());
            var adkTools = AdkToolBridge.toAdkTools(tools);
            var instruction = """
                    You are the CEO of an AI-powered startup advisory team. You coordinate a team \
                    of specialist agents to deliver comprehensive market analysis and strategic advice.

                    When a user asks you to analyze a market, product idea, or business opportunity, \
                    you MUST call ALL FOUR of these tools before giving your final answer:
                    1. web_search - gather market data and competitor info
                    2. analyze_strategy - SWOT analysis and positioning
                    3. financial_model - TAM/SAM/SOM and revenue projections
                    4. write_report - synthesize into an executive briefing

                    Always call every tool. Each represents a specialist on your team. \
                    After all tools respond, synthesize their findings into a cohesive briefing.""";

            var agent = LlmAgent.builder()
                    .name("startup-ceo")
                    .model(gemini)
                    .instruction(instruction)
                    .tools(adkTools.toArray(new com.google.adk.tools.BaseTool[0]))
                    .build();
            AdkAiSupport.setRunner(new InMemoryRunner(agent, "atmosphere"));
            logger.info("ADK configured with {} tools and CEO instruction", adkTools.size());
        };
    }
}
