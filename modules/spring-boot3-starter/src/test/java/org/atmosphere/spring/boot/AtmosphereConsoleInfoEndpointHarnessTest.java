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

import java.util.LinkedHashMap;
import java.util.Map;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code /api/console/info}'s {@code harness} field — the runtime-truth
 * view of the harness preset. The section appears only when the core preset
 * installer published its per-primitive states into the framework property bag
 * (i.e. the preset actually ran); configuration intent alone must never
 * surface it (Runtime Truth — Invariant #5).
 */
class AtmosphereConsoleInfoEndpointHarnessTest {

    @Test
    void publishedRuntimeStateAppearsInPayload() {
        var framework = new AtmosphereFramework();
        var states = new LinkedHashMap<String, String>();
        states.put("conversation-memory", "ACTIVE");
        states.put("durable-runs", "INACTIVE(no-journal)");
        states.put("skills", "CONVENTION");
        framework.getAtmosphereConfig().properties()
                .put(AtmosphereConsoleInfoEndpoint.HARNESS_RUNTIME_STATE_PROPERTY, states);

        var result = newEndpoint(framework).info();

        assertThat(result.get("harness")).isEqualTo(states);
    }

    @Test
    void absentWhenThePresetNeverRan() {
        var result = newEndpoint(new AtmosphereFramework()).info();
        assertThat(result).doesNotContainKey("harness");
    }

    @Test
    void emptyStateMapIsOmitted() {
        var framework = new AtmosphereFramework();
        framework.getAtmosphereConfig().properties()
                .put(AtmosphereConsoleInfoEndpoint.HARNESS_RUNTIME_STATE_PROPERTY, Map.of());

        var result = newEndpoint(framework).info();

        assertThat(result).doesNotContainKey("harness");
    }

    @Test
    void nonStringEntriesAreFilteredOut() {
        var framework = new AtmosphereFramework();
        framework.getAtmosphereConfig().properties()
                .put(AtmosphereConsoleInfoEndpoint.HARNESS_RUNTIME_STATE_PROPERTY,
                        Map.of("rounds", 5));

        var result = newEndpoint(framework).info();

        assertThat(result).doesNotContainKey("harness");
    }

    private AtmosphereConsoleInfoEndpoint newEndpoint(AtmosphereFramework framework) {
        // Mirrors the SB4 starter's pin; the boot3 endpoint takes no
        // ApplicationContext (no capability-flag detection on this starter).
        return new AtmosphereConsoleInfoEndpoint(new AtmosphereProperties(), framework);
    }
}
