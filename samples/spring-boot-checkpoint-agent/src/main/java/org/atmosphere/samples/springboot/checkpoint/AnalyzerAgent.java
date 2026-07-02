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

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.annotation.AgentScope;
import org.springframework.stereotype.Component;

/**
 * Analyzer specialist — performs the initial analysis whose results are
 * captured as a checkpoint for later human approval.
 *
 * <p>The analysis is exposed as an A2A skill ({@code analyze}) rather than a
 * plain {@code @AiTool} so the coordinator's {@code fleet.agent("analyzer")
 * .call("analyze", ...)} dispatch resolves to a registered skill. The skill's
 * artifact (the JSON below) becomes the {@code AgentCompleted} result that the
 * checkpoint snapshot captures, which is what lets {@code approve?by=alice}
 * recover the original request without an explicit {@code ?request=} override.
 */
@AgentScope(unrestricted = true,
        justification = "Checkpoint demo; analyzes arbitrary text so pause, fork and resume can be exercised on any conversation")
@Agent(name = "analyzer",
        description = "Analyzes a request and produces a structured recommendation")
@Component
public class AnalyzerAgent {

    @AgentSkill(id = "analyze", name = "Analyze",
            description = "Analyze the request and produce a recommendation with risk level")
    @AgentSkillHandler
    public void analyze(TaskContext task,
            @AgentSkillParam(name = "request", description = "The user request to analyze")
            String request) {
        // Deterministic fake analysis for the demo — in a real system this
        // would call an LLM or a domain model.
        var lower = request == null ? "" : request.toLowerCase();
        var risk = lower.contains("delete") || lower.contains("drop") || lower.contains("refund")
                ? "HIGH"
                : lower.contains("update") || lower.contains("modify") ? "MEDIUM" : "LOW";
        var json = "{\"request\":\"" + escape(request) + "\",\"risk\":\"" + risk
                + "\",\"recommendation\":\"requires " + (risk.equals("HIGH") ? "manual" : "automatic")
                + " approval\"}";
        task.addArtifact(Artifact.text(json));
        task.complete("Analysis complete (risk: " + risk + ")");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
