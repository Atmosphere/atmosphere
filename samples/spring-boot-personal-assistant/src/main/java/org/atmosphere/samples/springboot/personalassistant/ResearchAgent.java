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

/**
 * Research crew member — gathers background context on a topic. In this
 * sample the skill returns a deterministic canned summary so the sample
 * runs without internet; a production research agent would use a web
 * search MCP tool, with credentials resolved through the user's
 * {@code AgentIdentity} credential store (per v0.5 primitive 5).
 */
@Agent(
        name = "research-agent",
        skillFile = "skill:research-agent",
        description = "Summarizes background context on a topic so the primary assistant "
                + "can speak to it confidently.",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/research"
)
public class ResearchAgent {

    @AgentSkill(id = "summarize_topic", name = "Summarize Topic",
            description = "Return a short research brief on a single topic.",
            tags = {"research", "summary"})
    @AgentSkillHandler
    public void summarizeTopic(TaskContext task,
                               @AgentSkillParam(name = "topic",
                                       description = "The topic to research") String topic) {
        var report = "Research brief for: '" + topic + "'\n\n"
                + "- This sample agent does not hit the internet.\n"
                + "- A production research agent would resolve a web-search MCP "
                + "tool via ToolExtensibilityPoint, using credentials from the "
                + "user's per-agent CredentialStore.\n"
                + "- The point of this crew member is to demonstrate that the "
                + "primary assistant fans out through InMemoryProtocolBridge "
                + "and receives a structured artifact.\n";
        task.addArtifact(Artifact.text(report));
        task.complete("Summary generated");
    }
}
