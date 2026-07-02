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
package org.atmosphere.quarkus.deployment;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;

/**
 * A minimal {@code @Coordinator} used by {@link CoordinatorQuarkusRegistrationTest}
 * to prove a coordinator and its fleet wiring register under Quarkus. The fleet
 * references {@link EchoMcpAgent} as a local {@code @Agent} member.
 */
@Coordinator(name = "quarkus-fleet-test", description = "Coordinator registration test", version = "1.0.0")
@Fleet(@AgentRef(type = EchoMcpAgent.class))
public class FleetTestCoordinator {

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        session.send("coordinating: " + message + " over " + fleet.agents().size() + " agents");
        session.complete();
    }
}
