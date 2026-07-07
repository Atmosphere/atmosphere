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

import io.quarkus.test.QuarkusExtensionTest;

import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the durable governance-feedback config surface on Quarkus:
 * {@code quarkus.atmosphere.ai.governance.memory.*} keys bridge to the
 * {@code org.atmosphere.ai.governance.memory.*} framework init-params that
 * {@code GovernanceMemoryConfig.from(...)} reads inside {@code AiEndpointProcessor} — the
 * same seam the Spring Boot starter feeds, so the opt-in behaves identically across runtimes
 * (Correctness Invariant #7). This boot deploys no annotated endpoint, so the assertions pin
 * init-param <em>arrival</em> at the framework seam; the install behavior behind those params
 * is pinned by the core {@code GovernanceMemoryInstallerTest} / {@code GovernanceMemoryConfigTest}.
 */
public class GovernanceMemoryBuildStepTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(GovernanceMemoryBuildStepTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.ai.governance.memory.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.ai.governance.memory.ttl-seconds", "3600")
            .overrideConfigKey("quarkus.atmosphere.ai.governance.memory.confidence", "0.9")
            .overrideConfigKey("quarkus.atmosphere.ai.governance.memory.min-confidence", "0.2");

    @Test
    public void governanceMemoryInitParamsReachTheDeployedFramework() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        var cfg = framework.getAtmosphereConfig();
        assertEquals("true", cfg.getInitParameter("org.atmosphere.ai.governance.memory.enabled"),
                "enabled must bridge to the framework init-param");
        assertEquals("3600", cfg.getInitParameter("org.atmosphere.ai.governance.memory.ttl-seconds"),
                "ttl-seconds must bridge");
        assertEquals("0.9", cfg.getInitParameter("org.atmosphere.ai.governance.memory.confidence"),
                "confidence must bridge");
        assertEquals("0.2", cfg.getInitParameter("org.atmosphere.ai.governance.memory.min-confidence"),
                "min-confidence must bridge");
    }
}
