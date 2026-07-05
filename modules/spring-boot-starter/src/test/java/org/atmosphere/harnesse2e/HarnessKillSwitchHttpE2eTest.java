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
 * {@code /api/console/info} must report the primitives INACTIVE on a boot
 * that still completes (the 200 below proves the boot; the agent's serving
 * path is not driven here).
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

    static {
        // Isolate the workspace substrate (plans/ + files/ subtrees) from the
        // developer's ~/.atmosphere/workspace — the harness attach creates
        // real directories at annotation-scan time (same pattern as
        // HarnessToolRoundTripHttpE2eTest).
        try {
            System.setProperty("atmosphere.workspace.root",
                    java.nio.file.Files.createTempDirectory("atmosphere-harness-e2e").toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create isolated workspace root", e);
        }
    }

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

    @Test
    void killSwitchSuppressesThePlanningAndFilesystemPrimitives() throws Exception {
        var body = HarnessRuntimeTruthHttpE2eTest.consoleInfo(port);

        // The @Agent default is {ALL}, which includes PLANNING and FILESYSTEM
        // — the kill switch must beat it so neither the write_todos floor nor
        // the file-tool floor attaches, and the console must report the
        // suppression as runtime truth (INACTIVE, never the ACTIVE upgrade —
        // Invariant #5).
        assertTrue(body.contains("\"planning\":\"INACTIVE"),
                "the kill switch must suppress the planning primitive despite the "
                        + "@Agent default, got: " + body);
        assertFalse(body.contains("\"planning\":\"ACTIVE"),
                "planning must not report ACTIVE under the kill switch, got: " + body);
        assertTrue(body.contains("\"filesystem\":\"INACTIVE"),
                "the kill switch must suppress the filesystem primitive despite the "
                        + "@Agent default, got: " + body);
        assertFalse(body.contains("\"filesystem\":\"ACTIVE"),
                "filesystem must not report ACTIVE under the kill switch, got: " + body);
    }

    @SpringBootApplication
    static class TestApp {
    }
}
