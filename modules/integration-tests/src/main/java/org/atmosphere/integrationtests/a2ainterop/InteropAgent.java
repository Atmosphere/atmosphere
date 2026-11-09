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
package org.atmosphere.integrationtests.a2ainterop;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.agent.annotation.Agent;

/**
 * Headless agent driven by the official A2A Java SDK client in
 * {@code A2aSdkInteropIntegrationTest}.
 */
@Agent(name = "interop",
        description = "Echoes the text it is sent",
        endpoint = "/atmosphere/a2a/interop")
public class InteropAgent {

    @AgentSkill(id = "echo", name = "Echo",
            description = "Echo the text of the message")
    @AgentSkillHandler
    public void echo(TaskContext task, @AgentSkillParam(name = "text") String text) {
        task.addArtifact(Artifact.text("echo: " + text));
        task.complete("Echoed");
    }
}
