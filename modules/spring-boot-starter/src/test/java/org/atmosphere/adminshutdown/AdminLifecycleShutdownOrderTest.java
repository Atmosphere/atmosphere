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
package org.atmosphere.adminshutdown;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real servlet container and closes the context the way SIGTERM does.
 * The framework-owning {@code AtmosphereLifecycle} used to stop at
 * {@code DEFAULT_PHASE - 1}, ahead of the admin lifecycle at
 * {@code Integer.MAX_VALUE - 2}, so {@code framework.destroy()} nulled the
 * {@code BroadcasterFactory} before the admin stop removed its listener, and
 * every Spring Boot app logged {@code Failed to stop bean 'atmosphereAdminLifecycle'}
 * with a {@code NullPointerException} at shutdown.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminLifecycleShutdownOrderTest {

    /** Phase shared by the admin, interactions and voice lifecycles. */
    private static final int FRAMEWORK_DEPENDENT_PHASE = Integer.MAX_VALUE - 2;

    private static final AtomicReference<Boolean> destroyedAtProbeStop = new AtomicReference<>();

    @Test
    void frameworkIsDestroyedOnlyAfterTheAdminLifecycleStops(CapturedOutput output) {
        destroyedAtProbeStop.set(null);
        var context = new SpringApplicationBuilder(TestApp.class)
                .properties("server.port=0")
                .run();
        try {
            var framework = context.getBean(AtmosphereFramework.class);
            assertThat(framework.getBroadcasterFactory())
                    .as("precondition: the servlet container must have initialized the framework")
                    .isNotNull();
            assertThat(context.getBean("atmosphereAdminLifecycle", SmartLifecycle.class).isRunning())
                    .as("precondition: the admin lifecycle must have started")
                    .isTrue();
        } finally {
            context.close();
        }

        assertThat(destroyedAtProbeStop.get())
                .as("lifecycles in the admin phase must stop before the framework is destroyed")
                .isFalse();
        assertThat(output.getAll())
                .doesNotContain("Failed to stop bean 'atmosphereAdminLifecycle'")
                .doesNotContain("BroadcasterSetup.broadcasterFactory()\" is null");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** Records whether the framework is already destroyed when the admin phase stops. */
        @Bean
        SmartLifecycle adminPhaseProbe(AtmosphereFramework framework) {
            return new SmartLifecycle() {
                private volatile boolean running;

                @Override
                public void start() {
                    running = true;
                }

                @Override
                public void stop() {
                    destroyedAtProbeStop.set(framework.isDestroyed());
                    running = false;
                }

                @Override
                public boolean isRunning() {
                    return running;
                }

                @Override
                public int getPhase() {
                    return FRAMEWORK_DEPENDENT_PHASE;
                }
            };
        }
    }
}
