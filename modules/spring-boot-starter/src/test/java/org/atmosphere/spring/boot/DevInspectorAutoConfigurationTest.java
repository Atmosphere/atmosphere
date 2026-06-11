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
}
