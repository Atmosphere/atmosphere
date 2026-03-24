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

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;

/**
 * CEO agent that orchestrates a multi-agent startup advisory team.
 * Each {@code @AiTool} represents a specialist agent on the team:
 *
 * <ul>
 *   <li>{@code web_search} — Research Agent (scrapes the web via JSoup)</li>
 *   <li>{@code analyze_strategy} — Strategy Agent (LLM-powered analysis)</li>
 *   <li>{@code financial_model} — Finance Agent (computation + LLM)</li>
 *   <li>{@code write_report} — Writer Agent (LLM-powered synthesis)</li>
 * </ul>
 *
 * <p>All 5 Atmosphere AI backends are on the classpath (LangChain4j, Spring AI,
 * Google ADK, Embabel, built-in), demonstrating framework-agnostic architecture.</p>
 */
@Agent(name = "startup-ceo",
        skillFile = "prompts/ceo-skill.md",
        description = "AI startup CEO that coordinates research, strategy, finance, and writing agents")
public class CeoAgent {

    private static final Logger logger = LoggerFactory.getLogger(CeoAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Client {} connected to Startup Team", resource.uuid());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Client {} disconnected from Startup Team", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Startup query: {}", message);
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }

        // Programmatic multi-agent orchestration: call each specialist agent
        // and emit tool events so the frontend renders agent cards in real-time.

        // 1. Research Agent — web search
        session.emit(new AiEvent.ToolStart("web_search", Map.of("query", message, "num_results", "3")));
        var research = webSearch(message, "3");
        session.emit(new AiEvent.ToolResult("web_search", research));

        // 2. Strategy Agent — market analysis
        session.emit(new AiEvent.ToolStart("analyze_strategy",
                Map.of("market", message, "focus_area", "market entry")));
        var strategy = analyzeStrategy(message, research, "market entry timing and positioning");
        session.emit(new AiEvent.ToolResult("analyze_strategy", strategy));

        // 3. Finance Agent — projections
        session.emit(new AiEvent.ToolStart("financial_model",
                Map.of("market", message, "tam_estimate", "15", "growth_rate", "35")));
        var finance = financialModel(message, "15", "35", "SaaS $29/mo per seat");
        session.emit(new AiEvent.ToolResult("financial_model", finance));

        // 4. Writer Agent — report synthesis
        session.emit(new AiEvent.ToolStart("write_report",
                Map.of("title", message + " — Market Analysis")));
        var report = writeReport(message + " — Market Analysis", research + "\n" + strategy,
                "Comprehensive analysis with financial projections");
        session.emit(new AiEvent.ToolResult("write_report", report));

        // 5. CEO synthesis — stream via LLM
        var synthesisPrompt = String.format(
                "You are a startup CEO synthesizing findings from your team. "
                + "Based on these agent reports, write a concise executive briefing "
                + "with: 1) Executive Summary (3 bullets), 2) GO/NO-GO recommendation "
                + "with confidence level, 3) Key risks, 4) Recommended next steps.\n\n"
                + "RESEARCH FINDINGS:\n%s\n\n"
                + "STRATEGY ANALYSIS:\n%s\n\n"
                + "FINANCIAL MODEL:\n%s\n\n"
                + "Be concise and decisive. Use markdown.", research, strategy, finance);
        session.stream(synthesisPrompt);
    }

    // --- Research Agent: web scraping via JSoup ---

    @AiTool(name = "web_search",
            description = "Search the web for market data, news, competitors, and trends. "
                    + "Scrapes real websites and returns relevant excerpts. Use this to gather "
                    + "evidence before making strategic recommendations.")
    public String webSearch(
            @Param(value = "query", description = "Search query (e.g., 'AI developer tools market size 2026')") String query,
            @Param(value = "num_results", description = "Number of results to fetch (1-5)") String numResults) {
        logger.info("Research Agent: searching web for '{}'", query);

        int count;
        try {
            count = Math.min(5, Math.max(1, Integer.parseInt(numResults)));
        } catch (NumberFormatException e) {
            count = 3;
        }

        var results = new ArrayList<String>();
        try {
            var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var searchUrl = "https://html.duckduckgo.com/html/?q=" + encoded;
            var doc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (compatible; AtmosphereBot/1.0)")
                    .timeout(10_000)
                    .get();

            var entries = doc.select(".result");
            for (int i = 0; i < Math.min(count, entries.size()); i++) {
                var entry = entries.get(i);
                var title = entry.select(".result__title").text();
                var snippet = entry.select(".result__snippet").text();
                var link = entry.select(".result__url").text();
                if (!title.isBlank()) {
                    results.add(String.format("[%d] %s\n    URL: %s\n    %s", i + 1, title, link, snippet));
                }
            }
        } catch (IOException e) {
            logger.warn("Web search failed, using fallback: {}", e.getMessage());
        }

        if (results.isEmpty()) {
            return generateFallbackSearchResults(query);
        }

        var sb = new StringBuilder();
        sb.append("Web search results for: \"").append(query).append("\"\n");
        sb.append("Date: ").append(LocalDate.now()).append("\n\n");
        for (var r : results) {
            sb.append(r).append("\n\n");
        }
        return sb.toString();
    }

    // --- Strategy Agent: LLM-powered market analysis ---

    @AiTool(name = "analyze_strategy",
            description = "Analyze market research data and identify strategic opportunities, "
                    + "threats, competitive advantages, and market positioning. Returns a SWOT-style "
                    + "analysis with actionable recommendations.")
    public String analyzeStrategy(
            @Param(value = "market", description = "Market or industry being analyzed") String market,
            @Param(value = "research_findings", description = "Key findings from web research to analyze") String findings,
            @Param(value = "focus_area", description = "Specific strategic question (e.g., 'market entry timing')") String focusArea) {
        logger.info("Strategy Agent: analyzing {} market, focus: {}", market, focusArea);

        // The strategy analysis is powered by the coordinator's LLM reasoning.
        // This tool structures the analysis framework for the LLM to fill in.
        return String.format("""
                Strategic Analysis Framework for: %s
                Focus: %s

                MARKET CONTEXT (from research):
                %s

                Please analyze the above and provide:

                OPPORTUNITIES:
                - Identify 3 key market opportunities based on the research data
                - Estimate market timing (early/growth/mature)

                THREATS:
                - Identify 2-3 competitive threats
                - Assess barriers to entry

                COMPETITIVE POSITIONING:
                - Recommended differentiation strategy
                - Key value proposition

                STRATEGIC RECOMMENDATION:
                - Go/No-Go assessment with confidence level
                - Recommended market entry approach
                - Critical success factors
                """, market, focusArea, findings);
    }

    // --- Finance Agent: projections and budget ---

    @AiTool(name = "financial_model",
            description = "Build financial projections including TAM/SAM/SOM analysis, "
                    + "revenue estimates, burn rate, and funding requirements. Returns a financial "
                    + "model with 3-year projections.")
    public String financialModel(
            @Param(value = "market", description = "Target market") String market,
            @Param(value = "tam_estimate", description = "Total addressable market estimate in billions USD (e.g., '12')") String tamEstimate,
            @Param(value = "growth_rate", description = "Annual market growth rate as percentage (e.g., '35')") String growthRate,
            @Param(value = "pricing_model", description = "Pricing approach (e.g., 'SaaS $29/mo per seat')") String pricingModel) {
        logger.info("Finance Agent: modeling {} market (TAM: ${}B, growth: {}%)", market, tamEstimate, growthRate);

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
        double y1Revenue = som * 0.01 * 1_000_000_000;
        double y2Revenue = y1Revenue * (1 + growth) * 2.5;
        double y3Revenue = y2Revenue * (1 + growth) * 1.8;

        double monthlyBurn = 85_000;
        double runway18mo = monthlyBurn * 18;

        return String.format("""
                Financial Model: %s
                Pricing: %s

                MARKET SIZING:
                  TAM (Total Addressable Market):     $%.1fB
                  SAM (Serviceable Available Market):  $%.1fB (%.0f%% of TAM)
                  SOM (Serviceable Obtainable Market): $%.1fB (%.0f%% of SAM)

                REVENUE PROJECTIONS:
                  Year 1:  $%,.0f  (market entry, early adopters)
                  Year 2:  $%,.0f  (growth phase, team expansion)
                  Year 3:  $%,.0f  (scale phase, market leadership bid)

                COST STRUCTURE:
                  Monthly burn rate:    $%,.0f
                  Team (5 engineers):   $50,000/mo
                  Infrastructure:       $15,000/mo
                  Marketing:            $12,000/mo
                  Operations:           $8,000/mo

                FUNDING REQUIREMENTS:
                  Seed round:           $%,.0f (18 months runway)
                  Target break-even:    Month 14-16
                  Recommended raise:    $2M at $8M pre-money valuation

                KEY METRICS TO TRACK:
                  - MRR growth rate (target: 15%% MoM)
                  - CAC payback period (target: < 6 months)
                  - Net revenue retention (target: > 120%%)
                  - Monthly active users (leading indicator)
                """, market, pricingModel,
                tam, sam, (sam / tam) * 100, som, (som / sam) * 100,
                y1Revenue, y2Revenue, y3Revenue,
                monthlyBurn, runway18mo);
    }

    // --- Writer Agent: report synthesis ---

    @AiTool(name = "write_report",
            description = "Synthesize research findings, strategic analysis, and financial "
                    + "projections into a polished executive briefing. Returns a structured "
                    + "report suitable for investors or board presentation.")
    public String writeReport(
            @Param(value = "title", description = "Report title (e.g., 'AI Developer Tools Market Analysis')") String title,
            @Param(value = "key_findings", description = "Summary of key findings from research and analysis") String keyFindings,
            @Param(value = "recommendation", description = "Strategic recommendation (go/no-go and rationale)") String recommendation) {
        logger.info("Writer Agent: drafting report '{}'", title);

        return String.format("""
                =============================================
                EXECUTIVE BRIEFING: %s
                Date: %s
                Prepared by: Atmosphere AI Startup Team
                =============================================

                RESEARCH FINDINGS:
                %s

                STRATEGIC RECOMMENDATION:
                %s

                Please synthesize the above into a polished executive briefing with:
                1. Executive Summary (3 bullets)
                2. Market Opportunity (backed by data)
                3. Competitive Landscape
                4. Financial Outlook
                5. Risk Factors
                6. Recommended Next Steps

                Format as a professional report with clear headings and concise language.
                """, title.toUpperCase(), LocalDate.now(), keyFindings, recommendation);
    }

    private String generateFallbackSearchResults(String query) {
        var lower = query.toLowerCase();
        if (lower.contains("ai") && (lower.contains("developer") || lower.contains("coding"))) {
            return """
                    Web search results for: "%s" (cached/demo data)

                    [1] The AI Developer Tools Market Reaches $15B — TechCrunch
                        URL: techcrunch.com
                        The AI-powered developer tools market has grown 45%% YoY, reaching an
                        estimated $15B in 2026. Key players include GitHub Copilot, Cursor,
                        Windsurf, and Claude Code. Enterprise adoption is accelerating.

                    [2] Stack Overflow Survey 2026: 78%% of Developers Use AI Tools
                        URL: stackoverflow.com
                        Developer surveys show massive AI adoption. Code completion is the
                        #1 use case (78%%), followed by code review (45%%) and documentation
                        generation (38%%). Satisfaction rates average 7.2/10.

                    [3] Gartner: AI-Augmented Development Will Be Standard by 2028
                        URL: gartner.com
                        By 2028, 75%% of enterprise developers will use AI coding assistants,
                        up from 25%% in 2024. Market CAGR of 35%% projected through 2030.
                    """.formatted(query);
        }
        return """
                Web search results for: "%s" (cached/demo data)

                [1] Market Overview — Industry Report 2026
                    URL: marketresearch.com
                    The sector shows strong growth momentum with estimated 30%% CAGR.
                    Key trends include AI adoption, automation, and consolidation.

                [2] Competitive Landscape Analysis
                    URL: businessinsider.com
                    Major players are investing heavily in R&D. The market remains
                    fragmented with opportunities for differentiated entrants.

                [3] Funding Trends — Q1 2026
                    URL: crunchbase.com
                    VC investment in this sector reached $8.2B in Q1 2026, up 28%%
                    from the previous quarter. Seed-stage deals averaged $2.5M.
                """.formatted(query);
    }
}
