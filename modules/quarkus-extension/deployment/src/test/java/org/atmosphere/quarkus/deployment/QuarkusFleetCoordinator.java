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
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;

/**
 * Minimal {@code @Coordinator} fixture for {@link CoordinatorJandexBuildStepTest}.
 * The fleet is intentionally empty — the regression under test is registration
 * (the annotation reaching {@code CoordinatorProcessor} through the Quarkus
 * Jandex scan), not fleet dispatch.
 */
@Coordinator(name = "quark-fleet-lead", description = "Jandex scan regression coordinator")
@Fleet({})
public class QuarkusFleetCoordinator {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream("lead: " + message);
    }
}
