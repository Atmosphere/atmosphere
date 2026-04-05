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
package org.atmosphere.samples.springboot.checkpoint;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.springframework.stereotype.Component;

/**
 * Analyzer specialist — performs the initial analysis whose results are
 * captured as a checkpoint for later human approval.
 */
@Agent(name = "analyzer",
        description = "Analyzes a request and produces a structured recommendation")
@Component
public class AnalyzerAgent {

    @AiTool(name = "analyze",
            description = "Analyze the request and produce a recommendation with risk level")
    public String analyze(
            @Param(value = "request", description = "The user request to analyze")
            String request) {
        // Deterministic fake analysis for the demo — in a real system this
        // would call an LLM or a domain model.
        var lower = request == null ? "" : request.toLowerCase();
        var risk = lower.contains("delete") || lower.contains("drop") || lower.contains("refund")
                ? "HIGH"
                : lower.contains("update") || lower.contains("modify") ? "MEDIUM" : "LOW";
        return "{\"request\":\"" + escape(request) + "\",\"risk\":\"" + risk
                + "\",\"recommendation\":\"requires " + (risk.equals("HIGH") ? "manual" : "automatic")
                + " approval\"}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
