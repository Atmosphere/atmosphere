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
package org.atmosphere.integrationtests.coordinator;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.agent.annotation.Agent;

/**
 * Headless specialist agent for coordinator E2E tests.
 */
@Agent(name = "worker-alpha",
        description = "Alpha worker agent",
        endpoint = "/atmosphere/a2a/worker-alpha")
public class WorkerAlpha {

    @AgentSkill(id = "analyze", name = "Analyze",
            description = "Analyze a topic")
    @AgentSkillHandler
    public void analyze(TaskContext task,
                        @AgentSkillParam(name = "topic") String topic) {
        task.addArtifact(Artifact.text("Alpha analysis of: " + topic));
        task.complete("Analysis complete");
    }
}
