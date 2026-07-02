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

import java.util.List;
import java.util.Map;

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import org.atmosphere.cpr.AtmosphereHandlerWrapper;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves an {@code @Coordinator} class registers on <b>Quarkus</b> (Correctness
 * Invariant #7 — Mode Parity with Spring Boot / plain servlet). This would fail
 * without the {@code @Coordinator} entry in {@code AtmosphereProcessor}'s
 * annotation scan list and the indexing of the optional
 * {@code atmosphere-coordinator} jar: the build-time Jandex scan would neither
 * collect {@link FleetTestCoordinator} nor discover {@code CoordinatorProcessor}
 * (annotated {@code @AtmosphereAnnotation}), so the coordinator would silently
 * never register while {@code @Agent} / {@code @AiEndpoint} classes do.
 *
 * <p>{@code atmosphere-coordinator} (+ {@code atmosphere-agent} /
 * {@code atmosphere-mcp} for the fleet's {@link EchoMcpAgent} member) is forced
 * onto the synthetic app. The test asserts observable registration state: the
 * running framework serves an {@code AgentHandler} at the coordinator's
 * {@code /atmosphere/agent/quarkus-fleet-test} path.</p>
 */
public class CoordinatorQuarkusRegistrationTest {

    private static List<Dependency> coordinatorDeps() {
        var version = System.getProperty("atmosphere.project.version");
        // coordinator pulls atmosphere-agent transitively, but atmosphere-mcp is
        // optional everywhere (EchoMcpAgent compiles against @McpTool) and
        // atmosphere-a2a is optional too while LocalAgentTransport's fleet
        // dispatch requires its LocalDispatchable seam — both coordinator
        // samples declare a2a explicitly — so force all four.
        return List.of(
                new ArtifactDependency("org.atmosphere", "atmosphere-coordinator", null, "jar", version),
                new ArtifactDependency("org.atmosphere", "atmosphere-agent", null, "jar", version),
                new ArtifactDependency("org.atmosphere", "atmosphere-mcp", null, "jar", version),
                new ArtifactDependency("org.atmosphere", "atmosphere-a2a", null, "jar", version));
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setForcedDependencies(coordinatorDeps())
            .withApplicationRoot(jar -> jar.addClasses(
                    CoordinatorQuarkusRegistrationTest.class,
                    FleetTestCoordinator.class,
                    EchoMcpAgent.class))
            .overrideConfigKey("quarkus.atmosphere.packages", "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Test
    public void coordinatorHandlerIsRegisteredOnQuarkus() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework,
                "Atmosphere framework must be initialized at startup (loadOnStartup=1)");

        Map<String, AtmosphereHandlerWrapper> handlers = framework.getAtmosphereHandlers();
        var wrapper = handlers.entrySet().stream()
                .filter(e -> e.getKey().contains("/atmosphere/agent/quarkus-fleet-test"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        assertNotNull(wrapper,
                "@Coordinator must register an AtmosphereHandler at "
                        + "/atmosphere/agent/quarkus-fleet-test — registered paths: "
                        + handlers.keySet());
        assertEquals("org.atmosphere.agent.processor.AgentHandler",
                wrapper.atmosphereHandler().getClass().getName(),
                "the coordinator path must be served by CoordinatorProcessor's AgentHandler");
    }
}
