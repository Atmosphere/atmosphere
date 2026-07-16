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
package org.atmosphere.spring.boot;

import java.util.List;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code /api/console/info}'s {@code transport} field on the boot3
 * starter, in parity with the Spring Boot 4 starter
 * ({@code AtmosphereConsoleInfoEndpointModeTest}): only transports the console
 * ships an adapter for pass through; anything else — including a typo — is
 * reported as {@code atmosphere} so the console never tries to load a missing
 * adapter (Runtime Truth — Invariant #5).
 */
class AtmosphereConsoleInfoEndpointTransportTest {

    @Test
    void transportDefaultsToAtmosphereWhenUnset() {
        assertThat(newEndpoint(new AtmosphereProperties()).info())
                .containsEntry("transport", "atmosphere");
    }

    @Test
    void transportReflectsConfiguredForeignAdapter() {
        for (var t : List.of("grpc", "a2a", "ag-ui")) {
            var props = new AtmosphereProperties();
            props.setConsoleTransport(t);
            assertThat(newEndpoint(props).info())
                    .as("transport %s", t)
                    .containsEntry("transport", t);
        }
    }

    @Test
    void transportNormalizesCaseAndWhitespace() {
        var props = new AtmosphereProperties();
        props.setConsoleTransport("  AG-UI  ");
        assertThat(newEndpoint(props).info()).containsEntry("transport", "ag-ui");
    }

    @Test
    void unknownTransportFallsBackToAtmosphere() {
        var props = new AtmosphereProperties();
        props.setConsoleTransport("websocket-turbo");
        assertThat(newEndpoint(props).info()).containsEntry("transport", "atmosphere");
    }

    private AtmosphereConsoleInfoEndpoint newEndpoint(AtmosphereProperties properties) {
        return new AtmosphereConsoleInfoEndpoint(properties, new AtmosphereFramework());
    }
}
