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
package org.atmosphere.harnesse2e;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP e2e for the app-wide harness kill switch. Boots the same
 * {@link DefaultOnHarnessAgent} fixture as
 * {@link HarnessRuntimeTruthHttpE2eTest} — a bare {@code @Agent} whose
 * annotation default is the full harness — but with
 * {@code atmosphere.ai.harness.enabled=false}, and asserts the switch beats
 * the annotation through the real stack: the Spring bridge must deliver the
 * explicit {@code false} as an init-param (not drop it as falsy), and
 * {@code /api/console/info} must report the primitives INACTIVE while the
 * agent itself still boots and serves.
 *
 * <p>This is the operational/compliance posture the tri-state was designed
 * for: one property turns the batteries off fleet-wide without touching
 * annotations. The bridge-level tri-state is unit-pinned by
 * {@code HarnessAutoConfigurationTest}; this boot proves the suppression
 * end-to-end on a real annotated agent.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HarnessKillSwitchHttpE2eTest.TestApp.class,
        properties = {
                "atmosphere.packages=org.atmosphere.harnesse2e",
                "atmosphere.ai.harness.enabled=false"
        })
class HarnessKillSwitchHttpE2eTest {

    @LocalServerPort
    private int port;

    @Test
    void killSwitchBeatsTheAgentDefaultThroughTheRealStack() throws Exception {
        var body = HarnessRuntimeTruthHttpE2eTest.consoleInfo(port);

        assertTrue(body.contains("\"harness\":{"),
                "the kill switch must not hide the runtime-truth block — INACTIVE is "
                        + "reported, not omitted, got: " + body);
        assertTrue(body.contains("\"conversation-memory\":\"INACTIVE"),
                "the kill switch must suppress conversation memory despite the @Agent "
                        + "default, got: " + body);
        assertFalse(body.contains("\"conversation-memory\":\"ACTIVE\""),
                "conversation memory must not activate under the kill switch, got: " + body);
        assertFalse(body.contains("\"prompt-cache-default\":\"conservative\""),
                "the kill switch must not seed the prompt-cache default, got: " + body);
    }

    @SpringBootApplication
    static class TestApp {
    }
}
