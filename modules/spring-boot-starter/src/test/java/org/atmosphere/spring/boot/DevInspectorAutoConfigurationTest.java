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

import org.atmosphere.admin.ai.DevInspectorController;
import org.atmosphere.ai.devinspector.DevInspectorRecorder;
import org.atmosphere.ai.devinspector.DevInspectorRecorderHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the opt-in dev-inspector wiring (P2.20): off by default; installs recorder when enabled. */
class DevInspectorAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAdminAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetHolder() {
        DevInspectorRecorderHolder.reset();
    }

    @Test
    void offByDefaultLeavesRecorderAtNoop() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DevInspectorController.class);
            assertThat(DevInspectorRecorderHolder.get()).isSameAs(DevInspectorRecorder.NOOP);
        });
    }

    @Test
    void enabledInstallsRecorderAndController() {
        contextRunner
                .withPropertyValues("atmosphere.ai.dev-inspector.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DevInspectorController.class);
                    assertThat(DevInspectorRecorderHolder.get())
                            .as("enabling the inspector must install a real recorder on the live path")
                            .isNotSameAs(DevInspectorRecorder.NOOP);
                });
    }

    /**
     * Regression: the inspector bean was declared inside the
     * coordinator-gated configuration class, so an application without
     * {@code atmosphere-coordinator} silently got no recorder — the read
     * endpoint answered but every turn went unrecorded. The tests above cannot
     * catch that, because this module's own test classpath carries the
     * coordinator; only hiding it reproduces a plain AI app.
     */
    @Test
    void enabledInstallsRecorderWithoutTheCoordinatorOnTheClasspath() {
        contextRunner
                .withPropertyValues("atmosphere.ai.dev-inspector.enabled=true")
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        org.atmosphere.coordinator.fleet.AgentFleet.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DevInspectorController.class);
                    assertThat(DevInspectorRecorderHolder.get())
                            .as("the dev inspector has no coordinator dependency — it must "
                                    + "install without one")
                            .isNotSameAs(DevInspectorRecorder.NOOP);
                });
    }

    /**
     * Regression, opposite direction: hoisting the bean to the top-level
     * configuration to fix the case above made a servlet-only application
     * — no {@code atmosphere-ai} at all — fail to start with
     * {@code NoClassDefFoundError}, because a top-level {@code @Bean}
     * method's signature is resolved whenever the enclosing configuration is
     * introspected. The context must still refresh cleanly when the
     * dev-inspector types are absent, flag set or not.
     */
    @Test
    void absentDevInspectorTypesDoNotBreakContextRefresh() {
        contextRunner
                .withPropertyValues("atmosphere.ai.dev-inspector.enabled=true")
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        DevInspectorRecorder.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("atmosphereDevInspectorController");
                });
    }
}
