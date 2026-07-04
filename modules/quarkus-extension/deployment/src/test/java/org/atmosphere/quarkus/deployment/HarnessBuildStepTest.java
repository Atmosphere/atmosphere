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

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;

import org.atmosphere.ai.resume.DurableRunSpineHolder;
import org.atmosphere.quarkus.runtime.AtmosphereDurableRunsProducer;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the agent-harness preset config surface on Quarkus:
 * {@code quarkus.atmosphere.ai.harness.*} keys bridge to the
 * {@code org.atmosphere.ai.*} framework init-params read by
 * {@code AiEndpointProcessor}, an explicitly enabled harness implies the
 * durable-run spine (no explicit {@code durable-runs.enabled} needed), and the
 * {@code /api/console/info} servlet surfaces the preset's published
 * per-primitive runtime-state map (Invariant #5). This boot deploys no
 * annotated endpoint, so the assertions pin init-param <em>arrival</em> at
 * the framework seam; the readers' behavior behind those params is pinned by
 * the core {@code HarnessPresetTest} / {@code AiEndpointProcessorHarnessPresetTest}.
 */
public class HarnessBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(HarnessBuildStepTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.exclude-paths",
                    "/atmosphere/support,/atmosphere/ops")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.compaction", "summarizing")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.prompt-cache-default", "conservative")
            // Keep the implied spine hermetic — no on-disk SQLite journal.
            .overrideConfigKey("quarkus.atmosphere.durable-runs.journal", "memory");

    @Inject
    AtmosphereDurableRunsProducer producer;

    @TestHTTPResource("/api/console/info")
    URL consoleInfoUrl;

    @Test
    public void harnessInitParamsReachTheDeployedFramework() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        var cfg = framework.getAtmosphereConfig();
        assertEquals("true", cfg.getInitParameter("org.atmosphere.ai.harness.enabled"),
                "quarkus.atmosphere.ai.harness.enabled must bridge to the framework init-param");
        assertEquals("/atmosphere/support,/atmosphere/ops",
                cfg.getInitParameter("org.atmosphere.ai.harness.exclude-paths"),
                "exclude-paths must bridge comma-joined");
        assertEquals("summarizing", cfg.getInitParameter("org.atmosphere.ai.compaction"),
                "compaction must bridge to org.atmosphere.ai.compaction");
        assertEquals("conservative", cfg.getInitParameter("org.atmosphere.ai.prompt-cache.default"),
                "prompt-cache-default must bridge to org.atmosphere.ai.prompt-cache.default");
    }

    @Test
    public void harnessImpliesTheDurableRunSpine() {
        assertNotNull(producer, "AtmosphereDurableRunsProducer must be CDI-resolvable");
        assertTrue(producer.installed(),
                "harness.enabled=true must install the durable-run spine without an "
                        + "explicit quarkus.atmosphere.durable-runs.enabled=true");
        assertTrue(DurableRunSpineHolder.get().enabled(),
                "DurableRunSpineHolder must report an enabled spine");
    }

    @Test
    public void consoleInfoPublishesTheHarnessRuntimeStateMap() throws Exception {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        // Publish the per-primitive state map exactly where the core preset
        // does; the servlet must relay it verbatim (runtime truth, not intent).
        framework.getAtmosphereConfig().properties().put(
                "org.atmosphere.ai.harness.runtime-state",
                Map.of("durable-runs", "ACTIVE"));

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(consoleInfoUrl.toString())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "/api/console/info must respond");
        assertTrue(response.body().contains("\"harness\":{\"durable-runs\":\"ACTIVE\"}"),
                "the console info payload must carry the published harness runtime-state map: "
                        + response.body());
    }
}
