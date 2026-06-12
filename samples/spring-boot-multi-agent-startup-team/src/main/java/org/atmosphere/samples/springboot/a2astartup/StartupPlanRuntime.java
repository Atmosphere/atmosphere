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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Stand-in for the team's planning LLM. Returns one of four canned workflow
 * JSON blobs based on the goal so the sample runs deterministically without an
 * API key — the demonstration is the verifier, not the model. Real runs swap in
 * any {@link AgentRuntime} on the classpath; the
 * {@link org.atmosphere.verifier.PlanAndVerify} contract is identical.
 *
 * <p>The same plan source feeds both the live {@code CeoCoordinator} (which
 * verifies before dispatching) and the Console Validation tab examples.</p>
 *
 * <ul>
 *   <li><b>default</b> — benign market-analysis plan; clears the whole chain
 *       and executes.</li>
 *   <li><b>"confidential" / "financial model" to board</b> — pipes
 *       {@code financial_model} output into {@code publish_to_board.body};
 *       refused by the taint verifier.</li>
 *   <li><b>"requested" / over budget</b> — commits an externally-supplied amount
 *       the SMT layer cannot prove is within runway; refused.</li>
 *   <li><b>"skip research" / finances first</b> — calls {@code financial_model}
 *       before {@code web_search}; refused by the ordering automaton.</li>
 * </ul>
 */
public class StartupPlanRuntime implements AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(StartupPlanRuntime.class);

    /** Benign: research first, publish the report (not the financials), commit within runway. */
    static final String BENIGN_PLAN = """
            {
              "goal": "Analyze the market and brief the board",
              "steps": [
                { "label": "research",  "toolName": "web_search",
                  "arguments": { "query": "target market" }, "resultBinding": "research" },
                { "label": "model",     "toolName": "financial_model",
                  "arguments": { "market": "target market", "tam_estimate": "15" },
                  "resultBinding": "financials" },
                { "label": "strategy",  "toolName": "analyze_strategy",
                  "arguments": { "research": "@research" }, "resultBinding": "strategy" },
                { "label": "report",    "toolName": "write_report",
                  "arguments": { "title": "Market Analysis", "key_findings": "@strategy" },
                  "resultBinding": "report" },
                { "label": "runway",    "toolName": "check_runway",
                  "arguments": {}, "resultBinding": "runway" },
                { "label": "commit",    "toolName": "commit_budget",
                  "arguments": { "amount": "@runway" }, "resultBinding": "receipt" },
                { "label": "publish",   "toolName": "publish_to_board",
                  "arguments": { "body": "@report" }, "resultBinding": "published" }
              ]
            }
            """;

    /** Taint refusal: confidential financial model output reaches the external board portal. */
    static final String LEAK_PLAN = """
            {
              "goal": "Publish the confidential financial model to the board portal",
              "steps": [
                { "label": "research", "toolName": "web_search",
                  "arguments": { "query": "target market" }, "resultBinding": "research" },
                { "label": "model",    "toolName": "financial_model",
                  "arguments": { "market": "target market", "tam_estimate": "15" },
                  "resultBinding": "financials" },
                { "label": "leak",     "toolName": "publish_to_board",
                  "arguments": { "body": "@financials" }, "resultBinding": "published" }
              ]
            }
            """;

    /** SMT refusal: commit an externally-requested amount not provably within runway. */
    static final String OVER_BUDGET_PLAN = """
            {
              "goal": "Commit the full requested budget regardless of runway",
              "steps": [
                { "label": "research",  "toolName": "web_search",
                  "arguments": { "query": "target market" }, "resultBinding": "research" },
                { "label": "runway",    "toolName": "check_runway",
                  "arguments": {}, "resultBinding": "runway" },
                { "label": "ask",       "toolName": "request_budget",
                  "arguments": {}, "resultBinding": "requested" },
                { "label": "commit",    "toolName": "commit_budget",
                  "arguments": { "amount": "@requested" }, "resultBinding": "receipt" }
              ]
            }
            """;

    /** Automaton refusal: financial_model called before any web_search (research). */
    static final String OUT_OF_ORDER_PLAN = """
            {
              "goal": "Skip research and jump straight to the financial model",
              "steps": [
                { "label": "model",    "toolName": "financial_model",
                  "arguments": { "market": "target market", "tam_estimate": "15" },
                  "resultBinding": "financials" },
                { "label": "report",   "toolName": "write_report",
                  "arguments": { "title": "Premature", "key_findings": "@financials" },
                  "resultBinding": "report" }
              ]
            }
            """;

    @Override
    public String name() {
        return "startup-plan";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int priority() {
        return -1; // never auto-selected — the sample wires it explicitly
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // no-op
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of();
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        session.send(generate(context));
        session.complete();
    }

    @Override
    public String generate(AgentExecutionContext context) {
        String goal = context.message() == null ? "" : context.message().toLowerCase();
        logger.info("StartupPlanRuntime planning for goal: '{}'", context.message());
        if (goal.contains("skip research") || goal.contains("before any research")
                || goal.contains("straight to the financial")) {
            return OUT_OF_ORDER_PLAN;
        }
        if (goal.contains("requested budget") || goal.contains("over budget")
                || goal.contains("regardless of runway") || goal.contains("full budget")) {
            return OVER_BUDGET_PLAN;
        }
        if (goal.contains("confidential") || goal.contains("leak")
                || (goal.contains("financial model") && goal.contains("board"))) {
            return LEAK_PLAN;
        }
        return BENIGN_PLAN;
    }
}
