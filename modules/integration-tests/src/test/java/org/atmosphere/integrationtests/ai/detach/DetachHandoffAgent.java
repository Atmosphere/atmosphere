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
package org.atmosphere.integrationtests.ai.detach;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;

/**
 * An {@code @Agent} that hands every prompt off to {@link DetachToolAgent},
 * as a support desk hands a refund to billing: the turn's answer, and its
 * tool call, come from the target agent's run.
 */
@Agent(name = DetachHandoffAgent.NAME, description = "Hands every prompt off to the tool agent")
public class DetachHandoffAgent {

    public static final String NAME = "detach-handoff";

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.handoff(DetachToolAgent.NAME, message);
    }
}
