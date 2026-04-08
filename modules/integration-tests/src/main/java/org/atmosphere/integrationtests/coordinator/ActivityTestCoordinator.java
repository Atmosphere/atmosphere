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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.StreamingActivityListener;

import java.util.Map;

/**
 * Test coordinator that wires {@link StreamingActivityListener} for E2E
 * verification of agent activity events on the wire.
 */
@Coordinator(name = "activity-coordinator",
        description = "Test coordinator for activity streaming E2E tests")
@Fleet({
        @AgentRef(type = WorkerAlpha.class),
        @AgentRef(type = WorkerBeta.class)
})
public class ActivityTestCoordinator {

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        // Wire per-session activity streaming — clients see agent-step events in real time
        var liveFleet = fleet.withActivityListener(new StreamingActivityListener(session));

        // Call alpha (will emit Thinking -> Completed agent-step events)
        var alphaResult = liveFleet.agent("worker-alpha")
                .call("analyze", Map.of("topic", message));

        // Call beta
        var betaResult = liveFleet.agent("worker-beta")
                .call("summarize", Map.of("content", alphaResult.text()));

        session.send("Activity synthesis — Alpha: [" + alphaResult.text()
                + "], Beta: [" + betaResult.text() + "]");
        session.complete();
    }
}
