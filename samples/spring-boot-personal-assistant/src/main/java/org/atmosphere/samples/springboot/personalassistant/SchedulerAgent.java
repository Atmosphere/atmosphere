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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.agent.annotation.Agent;

import java.time.LocalDate;

/**
 * Scheduler crew member — proposes meeting slots. Demonstrates a headless
 * crew agent reached through {@code InMemoryProtocolBridge} when called
 * from {@code PrimaryAssistant}, or over A2A when the coordinator lives
 * in a different process.
 *
 * <p>This sample is intentionally minimal. A production scheduler would
 * integrate a calendar MCP server via the
 * {@code ToolExtensibilityPoint} primitive using per-user credentials
 * resolved through {@code AgentIdentity}'s {@code CredentialStore}.</p>
 */
@Agent(
        name = "scheduler-agent",
        skillFile = "skill:scheduler-agent",
        description = "Proposes meeting slots and drafts calendar invites for the user.",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/scheduler"
)
public class SchedulerAgent {

    @AgentSkill(id = "propose_slots", name = "Propose Meeting Slots",
            description = "Suggest meeting slots for the given date range.",
            tags = {"calendar", "scheduling"})
    @AgentSkillHandler
    public void proposeSlots(TaskContext task,
                             @AgentSkillParam(name = "topic",
                                     description = "Meeting topic") String topic,
                             @AgentSkillParam(name = "date_hint",
                                     description = "Preferred date (ISO-8601, optional)") String dateHint) {
        var target = parseOrToday(dateHint);
        var report = "Proposed slots for '" + topic + "' on " + target + ":\n"
                + "  - 09:30 – 10:00 (focus block)\n"
                + "  - 14:00 – 14:30 (after standup)\n"
                + "  - 16:30 – 17:00 (end-of-day wrap)\n\n"
                + "NOTE: this sample does not hit a real calendar. A production "
                + "scheduler would resolve the user's calendar MCP tool via "
                + "ToolExtensibilityPoint + per-user credentials from "
                + "AgentIdentity.";
        task.addArtifact(Artifact.text(report));
        task.complete("Proposed three slots");
    }

    private static LocalDate parseOrToday(String hint) {
        if (hint == null || hint.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(hint);
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }
}
