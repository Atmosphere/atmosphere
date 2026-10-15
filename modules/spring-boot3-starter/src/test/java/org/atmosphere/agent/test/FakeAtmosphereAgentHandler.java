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
package org.atmosphere.agent.test;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;

/**
 * Test stand-in for an agent-shaped handler — placed in
 * {@code org.atmosphere.agent.test} so the package-prefix check in
 * {@code AtmosphereConsoleInfoEndpoint#detectMode}
 * ({@code "org.atmosphere.agent."}) classifies it as AI without requiring
 * a compile-time dep on {@code modules/agent}.
 */
public class FakeAtmosphereAgentHandler implements AtmosphereHandler {
    @Override
    public void onRequest(AtmosphereResource resource) {
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) {
    }

    @Override
    public void destroy() {
    }
}
