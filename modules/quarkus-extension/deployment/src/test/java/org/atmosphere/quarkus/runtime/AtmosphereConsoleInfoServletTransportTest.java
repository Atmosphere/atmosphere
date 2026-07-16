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
package org.atmosphere.quarkus.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@code /api/console/info}'s {@code transport} validation on the Quarkus
 * path in parity with the Spring starter
 * ({@code AtmosphereConsoleInfoEndpointModeTest}): only transports the console
 * ships an adapter for pass through; anything else — including a typo — is
 * reported as {@code atmosphere} so the console never tries to load a missing
 * adapter (Runtime Truth — Invariant #5). Lives in the runtime package (from
 * the deployment test tree) for package-private access, like the servlet's
 * other collaborators.
 */
class AtmosphereConsoleInfoServletTransportTest {

    @Test
    void unsetDefaultsToAtmosphere() {
        assertEquals("atmosphere", AtmosphereConsoleInfoServlet.validateTransport(null));
        assertEquals("atmosphere", AtmosphereConsoleInfoServlet.validateTransport(""));
        assertEquals("atmosphere", AtmosphereConsoleInfoServlet.validateTransport("  "));
    }

    @Test
    void knownForeignTransportsPassThrough() {
        assertEquals("grpc", AtmosphereConsoleInfoServlet.validateTransport("grpc"));
        assertEquals("a2a", AtmosphereConsoleInfoServlet.validateTransport("a2a"));
        assertEquals("ag-ui", AtmosphereConsoleInfoServlet.validateTransport("ag-ui"));
    }

    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals("ag-ui", AtmosphereConsoleInfoServlet.validateTransport("  AG-UI  "));
        assertEquals("grpc", AtmosphereConsoleInfoServlet.validateTransport("GRPC"));
    }

    @Test
    void unknownValueFallsBackToAtmosphere() {
        assertEquals("atmosphere", AtmosphereConsoleInfoServlet.validateTransport("websocket-turbo"));
        assertEquals("atmosphere", AtmosphereConsoleInfoServlet.validateTransport("atmospherex"));
    }
}
