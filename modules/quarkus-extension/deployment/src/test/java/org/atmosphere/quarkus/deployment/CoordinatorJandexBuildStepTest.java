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

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the {@code @Coordinator} Jandex gap: the annotation was
 * absent from {@code AtmosphereProcessor}'s optional scan list and
 * {@code atmosphere-coordinator} was never indexed, so {@code @Coordinator}
 * classes silently never registered on Quarkus (they worked on Spring Boot and
 * plain servlet). This boots a real Quarkus app with a {@code @Coordinator}
 * class and asserts the fleet handler is registered on the deployed
 * {@code AtmosphereFramework} — it FAILS without both halves of the fix
 * (the {@code OPTIONAL_ANNOTATIONS} entry and the
 * {@code IndexDependencyBuildItem} for {@code atmosphere-coordinator}).
 */
public class CoordinatorJandexBuildStepTest {

    private static List<Dependency> coordinatorDeps() {
        var version = System.getProperty("atmosphere.project.version");
        // atmosphere-ai and atmosphere-agent transit from atmosphere-coordinator.
        return List.of(new ArtifactDependency(
                "org.atmosphere", "atmosphere-coordinator", null, "jar", version));
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setForcedDependencies(coordinatorDeps())
            .withApplicationRoot(jar -> jar.addClasses(
                    CoordinatorJandexBuildStepTest.class, QuarkusFleetCoordinator.class))
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Test
    public void coordinatorClassRegistersFleetHandlerOnQuarkus() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "the Atmosphere framework must be initialized at startup");
        assertTrue(framework.getAtmosphereHandlers().containsKey("/atmosphere/agent/quark-fleet-lead"),
                "@Coordinator(name=quark-fleet-lead) must register its fleet handler at "
                        + "/atmosphere/agent/quark-fleet-lead — got: "
                        + framework.getAtmosphereHandlers().keySet());
    }
}
