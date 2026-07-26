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

import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.GatewayProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code atmosphere.ai.gateway.profile} opt-in: absent the property
 * the process-wide holder keeps its permissive default (no enforcement is
 * forced on an existing deployment); with {@code =production} the installer
 * publishes the tightened limits and a separately-sized anonymous bucket.
 */
class AtmosphereGatewayProfileAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtmosphereAutoConfiguration.class,
                    AtmosphereAiAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetHolder() {
        // The installer writes a process-wide holder; reset around each run so
        // cross-test pollution cannot pass an assertion by accident.
        AiGatewayHolder.reset();
    }

    @Test
    void withoutTheProfilePropertyTheDefaultStaysPermissive() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(
                    AtmosphereAiAutoConfiguration.GatewayProfileInstaller.class);
            // The permissive default admits far past any production ceiling.
            for (var i = 0; i < 1_000; i++) {
                assertThat(AiGatewayHolder.get().admit("alice", "built-in", "m").accepted())
                        .as("no opt-in means no enforcement change")
                        .isTrue();
            }
        });
    }

    @Test
    void productionProfileInstallsTheTightenedLimits() {
        contextRunner
                .withPropertyValues("atmosphere.ai.gateway.profile=production")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            AtmosphereAiAutoConfiguration.GatewayProfileInstaller.class);
                    var installed = AiGatewayHolder.get();
                    assertThat(installed.rateLimiter().maxRequests())
                            .isEqualTo(GatewayProfiles.PRODUCTION_MAX_REQUESTS_PER_WINDOW);
                    assertThat(installed.rateLimiter().window().toSeconds())
                            .isEqualTo(GatewayProfiles.PRODUCTION_WINDOW_SECONDS);
                    assertThat(installed.hasDedicatedAnonymousLimiter())
                            .as("the production profile separates the anonymous bucket")
                            .isTrue();
                    assertThat(installed.anonymousRateLimiter().maxRequests())
                            .isEqualTo(GatewayProfiles.PRODUCTION_MAX_REQUESTS_PER_WINDOW
                                    / GatewayProfiles.PRODUCTION_ANONYMOUS_DIVISOR);
                });
    }

    @Test
    void operatorOverridesWinOverProfileDefaults() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.gateway.profile=production",
                        "atmosphere.ai.gateway.max-requests-per-window=7",
                        "atmosphere.ai.gateway.window-seconds=60",
                        "atmosphere.ai.gateway.anonymous-max-requests=2")
                .run(context -> {
                    var installed = AiGatewayHolder.get();
                    assertThat(installed.rateLimiter().maxRequests()).isEqualTo(7);
                    assertThat(installed.rateLimiter().window().toSeconds()).isEqualTo(60);
                    assertThat(installed.anonymousRateLimiter().maxRequests()).isEqualTo(2);

                    // Drive it: the anonymous bucket exhausts on its own ceiling
                    // without spending a named principal's budget.
                    assertThat(installed.admit(AiGateway.ANONYMOUS_USER, "p", "m").accepted())
                            .isTrue();
                    assertThat(installed.admit(AiGateway.ANONYMOUS_USER, "p", "m").accepted())
                            .isTrue();
                    assertThat(installed.admit(AiGateway.ANONYMOUS_USER, "p", "m").accepted())
                            .as("anonymous callers share one bucket capped at 2")
                            .isFalse();
                    assertThat(installed.admit("alice", "p", "m").accepted())
                            .as("a named principal is unaffected by anonymous exhaustion")
                            .isTrue();
                });
    }

    @Test
    void contextShutdownRestoresThePermissiveDefault() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.gateway.profile=production",
                        "atmosphere.ai.gateway.max-requests-per-window=1")
                .run(context -> {
                    assertThat(AiGatewayHolder.get().rateLimiter().maxRequests()).isEqualTo(1);
                });
        // Outside run(...) the context is closed; DisposableBean must have
        // restored the default rather than leaking a 1-call/window gateway.
        assertThat(AiGatewayHolder.get().rateLimiter().maxRequests())
                .as("shutdown must restore the permissive default (Ownership)")
                .isGreaterThan(1);
    }
}
