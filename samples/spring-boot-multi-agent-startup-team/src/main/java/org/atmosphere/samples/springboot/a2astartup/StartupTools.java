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

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.verifier.annotation.RequiresCapability;
import org.atmosphere.verifier.annotation.Sink;

/**
 * The startup team's <em>consequential</em> actions, modelled as verifiable
 * tools. The {@code CeoCoordinator} dispatches the equivalent work to A2A
 * specialist agents; this tool set is what the plan-and-verify chain reasons
 * over so the whole team's plan can be checked <em>before</em> any agent runs.
 *
 * <p>The security properties travel with the parameters, single-sourced from
 * annotations (no parallel policy file):</p>
 * <ul>
 *   <li>{@code financial_model} produces confidential projections. The
 *       {@link Sink} on {@link #publishToBoard}'s {@code body} forbids that
 *       output from ever reaching the external board portal — the
 *       {@code SinkScanner} derives the {@code TaintRule} from it.</li>
 *   <li>{@code commit_budget} requires the {@code treasury}
 *       {@link RequiresCapability capability} (least authority), and its
 *       {@code amount} is bounded by a numeric invariant
 *       ({@code amount <= ref(runway)}) the SMT layer discharges.</li>
 *   <li>The ordering automaton requires {@code web_search} (research) before
 *       {@code financial_model} / {@code analyze_strategy}.</li>
 * </ul>
 *
 * <p>Every method body is a deterministic stub so the sample runs without an
 * API key — the point of the demonstration is the verifier, not the model. The
 * tools whose plans the verifier refuses are <em>unreachable</em>: the offending
 * call never executes.</p>
 */
public class StartupTools {

    @AiTool(name = "web_search",
            description = "Search the web for market signal (returns untrusted external content)")
    public String webSearch(
            @Param(value = "query", description = "Search query") String query) {
        return "[web] TechCrunch: '" + query + "' segment growing ~30% YoY; "
                + "incumbents: Acme, Globex. Note: external/untrusted content.";
    }

    @AiTool(name = "financial_model",
            description = "Build a confidential financial model (TAM, burn, projections)")
    public String financialModel(
            @Param(value = "market", description = "Target market") String market,
            @Param(value = "tam_estimate", description = "TAM in billions USD") String tamEstimate) {
        // Confidential — must not leave the company (see the @Sink on publish_to_board.body).
        return "[CONFIDENTIAL] " + market + ": TAM $" + tamEstimate + "B, "
                + "Year-1 burn $1.8M, runway 14mo, projected ARR $4.2M.";
    }

    @AiTool(name = "analyze_strategy",
            description = "Produce a go-to-market strategy from research findings")
    public String analyzeStrategy(
            @Param(value = "research", description = "Research findings to base strategy on") String research) {
        return "Strategy: land-and-expand, design-partner motion, usage-based pricing. "
                + "Basis: " + (research == null ? "(none)" : research);
    }

    @AiTool(name = "write_report",
            description = "Synthesize findings into an internal briefing")
    public String writeReport(
            @Param(value = "title", description = "Report title") String title,
            @Param(value = "key_findings", description = "Findings to synthesize") String keyFindings) {
        return "# " + title + "\n" + (keyFindings == null ? "" : keyFindings);
    }

    @AiTool(name = "check_runway",
            description = "Return the company's remaining runway budget (USD), the spend ceiling")
    public String checkRunway() {
        // A real tool reads the books; the figure is irrelevant to the SMT proof,
        // which is symbolic and holds for every possible runway value.
        return "250000";
    }

    @AiTool(name = "request_budget",
            description = "Return the budget amount the prompt asked to commit (externally-supplied)")
    public String requestBudget() {
        // Attacker/prompt-influenceable: no runtime guarantee this is <= runway,
        // so a plan that commits @requested cannot be proven safe and is refused.
        return "5000000";
    }

    @AiTool(name = "commit_budget",
            description = "Commit a spend amount (must stay within runway; requires treasury authority)")
    @RequiresCapability("treasury")
    public String commitBudget(
            @Param(value = "amount", description = "Amount to commit in USD")
            String amount) {
        // Unreachable for any plan the SMT layer cannot prove keeps
        // amount <= ref(runway) — the verifier refuses such plans first.
        return "OK — committed $" + amount;
    }

    @AiTool(name = "publish_to_board",
            description = "Publish a briefing to the external board portal")
    public String publishToBoard(
            @Param(value = "body", description = "Briefing body to publish")
            @Sink(forbidden = {"financial_model"}, name = "no-confidential-financials-to-board")
            String body) {
        // Unreachable for any plan that pipes financial_model output here — the
        // verifier refuses such plans before this method runs.
        return "OK — published " + (body == null ? 0 : body.length()) + " bytes to the board portal";
    }
}
