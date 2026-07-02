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
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.springframework.stereotype.Component;

/**
 * Approver specialist — executes the action decided by the analyzer.
 * Invoked directly from {@code CheckpointController#approve} after a human (or
 * an automated gate) approves a checkpoint written by the analyzer; its
 * {@code String} return is surfaced as the approval result.
 */
@AgentScope(unrestricted = true,
        justification = "Checkpoint demo; reviews whatever the analyzer produced — input is the demo's own pipeline")
@Agent(name = "approver",
        description = "Executes an approved action")
@Component
public class ApproverAgent {

    @AiTool(name = "execute",
            description = "Execute the approved action")
    public String execute(
            @Param(value = "request", description = "The original request to execute")
            String request,
            @Param(value = "approved_by", description = "Identifier of the approver")
            String approvedBy) {
        return "Executed '" + request + "' approved by " + approvedBy;
    }
}
