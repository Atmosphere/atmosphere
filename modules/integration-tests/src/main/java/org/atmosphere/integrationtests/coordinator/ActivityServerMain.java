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

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;

/**
 * Standalone launcher for the activity coordinator test server.
 * Used for manual/chrome-devtools testing.
 */
public class ActivityServerMain {

    public static void main(String[] args) throws Exception {
        var server = new EmbeddedAtmosphereServer()
                .withPort(9876)
                .withAnnotationPackage("org.atmosphere.integrationtests.coordinator")
                .withInitParam("org.atmosphere.annotation.packages",
                        "org.atmosphere.agent.processor,"
                                + "org.atmosphere.coordinator.processor,"
                                + "org.atmosphere.ai.processor");
        server.start();
        System.out.println("Activity coordinator server started on http://localhost:"
                + server.getPort());
        System.out.println("WebSocket: ws://localhost:" + server.getPort()
                + "/atmosphere/agent/activity-coordinator");
        Thread.currentThread().join();
    }
}
