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
import org.atmosphere.config.service.Ready;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Test coordinator for E2E integration tests with two headless agents
 * (worker-alpha and worker-beta).
 */
@Coordinator(name = "test-coordinator",
        description = "Test coordinator for E2E tests")
@Fleet({
        @AgentRef(type = WorkerAlpha.class),
        @AgentRef(type = WorkerBeta.class)
})
public class TestCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(TestCoordinator.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Test coordinator: client {} connected", resource.uuid());
    }

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        logger.info("Coordinator received: {}", message);

        // Sequential: call alpha first
        var alphaResult = fleet.agent("worker-alpha")
                .call("analyze", Map.of("topic", message));

        // Parallel: call beta (in a real scenario there'd be more agents)
        var results = fleet.parallel(
                fleet.call("worker-beta", "summarize",
                        Map.of("content", alphaResult.text()))
        );

        // Synthesize results
        var synthesis = String.format("Coordinator synthesis — Alpha: [%s], Beta: [%s]",
                alphaResult.text(), results.get("worker-beta").text());

        session.send(synthesis);
        session.complete(synthesis);
    }
}
