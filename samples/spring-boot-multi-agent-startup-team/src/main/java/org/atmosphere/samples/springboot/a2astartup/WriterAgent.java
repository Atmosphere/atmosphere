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

import java.time.LocalDate;

/**
 * Independent Writer Agent — synthesizes findings into executive briefings.
 * Discoverable at {@code /atmosphere/a2a/writer/agent.json}.
 */
@Agent(
        name = "writer-agent",
        skillFile = "prompts/writer-skill.md",
        description = "Report writing agent that synthesizes research, strategy, and financial data into executive briefings",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/writer"
)
public class WriterAgent {

    private static final Logger logger = LoggerFactory.getLogger(WriterAgent.class);

    @AgentSkill(id = "write_report", name = "Write Report",
            description = "Synthesize findings into a polished executive briefing",
            tags = {"writing", "report", "briefing"})
    @AgentSkillHandler
    public void writeReport(TaskContext task,
                            @AgentSkillParam(name = "title", description = "Report title") String title,
                            @AgentSkillParam(name = "key_findings", description = "Summary of findings") String keyFindings,
                            @AgentSkillParam(name = "recommendation", description = "Strategic recommendation") String recommendation) {
        task.updateStatus(TaskState.WORKING, "Drafting executive briefing: " + title);
        logger.info("Writer Agent: drafting report '{}'", title);

        var report = String.format("""
                =============================================
                EXECUTIVE BRIEFING: %s
                Date: %s
                Prepared by: Atmosphere AI Startup Team
                =============================================

                RESEARCH FINDINGS:
                %s

                STRATEGIC RECOMMENDATION:
                %s

                ---
                This report was assembled by the Writer Agent
                from data provided by Research, Strategy, and
                Finance agents via the A2A protocol.
                """, title.toUpperCase(), LocalDate.now(),
                keyFindings.length() > 500 ? keyFindings.substring(0, 500) + "..." : keyFindings,
                recommendation);

        task.addArtifact(Artifact.text(report));
        task.complete("Executive briefing complete");
    }
}
