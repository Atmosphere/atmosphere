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
 * Drafter crew member — drafts short-form messages (emails, replies,
 * status notes) on behalf of the user. Keeps the primary assistant free
 * to manage the conversation while delegation handles composition work.
 */
@Agent(
        name = "drafter-agent",
        skillFile = "skill:drafter-agent",
        description = "Drafts short-form messages (emails, replies, status updates) "
                + "using the user's preferred tone from the workspace SOUL.md.",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/drafter"
)
public class DrafterAgent {

    @AgentSkill(id = "draft_message", name = "Draft Message",
            description = "Return a short draft for the given recipient and intent.",
            tags = {"writing", "communication"})
    @AgentSkillHandler
    public void draftMessage(TaskContext task,
                             @AgentSkillParam(name = "recipient",
                                     description = "Who the message is for") String recipient,
                             @AgentSkillParam(name = "intent",
                                     description = "What the message needs to convey") String intent) {
        var draft = "Draft for " + recipient + "\n\n"
                + "Subject: " + intent + "\n\n"
                + "Hi " + recipient + ",\n\n"
                + "Quick note on " + intent + ". Happy to go deeper if useful.\n\n"
                + "---\n"
                + "This draft is intentionally short. A production drafter would pull "
                + "tone and signature from the workspace SOUL.md / IDENTITY.md via "
                + "AgentState.getRules() and personalize against the user profile.";
        task.addArtifact(Artifact.text(draft));
        task.complete("Draft produced");
    }
}
