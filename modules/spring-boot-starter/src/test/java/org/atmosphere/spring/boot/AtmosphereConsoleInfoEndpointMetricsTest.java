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

import java.util.List;
import java.util.Map;

import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the console's {@code hasMetrics} flag to the actuator's <em>web
 * exposure</em> registry rather than to bean or classpath presence.
 *
 * <p>Boot ships the {@code MetricsEndpoint} bean whenever spring-boot-actuator
 * is on the classpath, but its default
 * {@code management.endpoints.web.exposure.include} is health-only — so a
 * bean-presence gate advertises a {@code /actuator/metrics} that answers 404 and
 * points the Observability tab at a dead URL. These tests hold the flag to what
 * the actuator genuinely serves (Runtime Truth — Invariant #5).</p>
 */
class AtmosphereConsoleInfoEndpointMetricsTest {

    @Test
    void reportsThePathWhenTheMetricsEndpointIsWebExposed() {
        var result = newEndpoint(new MockEnvironment(), "metrics").info();

        assertThat(result).containsEntry("hasMetrics", true);
        assertThat(result).containsEntry("metricsFormat", "actuator");
        assertThat(result).containsEntry("metricsPath", "/actuator/metrics");
    }

    @Test
    void honoursARelocatedManagementBasePath() {
        var environment = new MockEnvironment()
                .withProperty("management.endpoints.web.base-path", "/manage/");

        var result = newEndpoint(environment, "metrics").info();

        assertThat(result).containsEntry("metricsPath", "/manage/metrics");
    }

    @Test
    void reportsNoPlaneWhenTheEndpointIsOnTheClasspathButNotExposed() {
        // The supplier exists (actuator is present) and serves health only —
        // exactly Boot's default exposure. /actuator/metrics would 404 here.
        var result = newEndpoint(new MockEnvironment(), "health").info();

        assertThat(result).containsEntry("hasMetrics", false);
        assertThat(result).doesNotContainKey("metricsPath");
        assertThat(result).doesNotContainKey("metricsFormat");
    }

    @Test
    void reportsNoPlaneWhenManagementListensOnItsOwnPort() {
        // The console is served from the application port; a management server
        // on 9001 is not reachable from its origin, so advertising the path
        // would ship a tab whose every fetch fails.
        var environment = new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("management.server.port", "9001");

        var result = newEndpoint(environment, "metrics").info();

        assertThat(result).containsEntry("hasMetrics", false);
        assertThat(result).doesNotContainKey("metricsPath");
    }

    @Test
    void reportsNoPlaneWithoutAnActuator() {
        var context = mock(ApplicationContext.class);
        when(context.getClassLoader()).thenReturn(getClass().getClassLoader());
        when(context.getEnvironment()).thenReturn(new MockEnvironment());
        when(context.getBeanNamesForType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenReturn(new String[0]);

        var result = new AtmosphereConsoleInfoEndpoint(
                new AtmosphereProperties(), framework(), context).info();

        assertThat(result).containsEntry("hasMetrics", false);
        assertThat(result).doesNotContainKey("metricsPath");
    }

    /**
     * A context whose actuator exposes exactly one endpoint under the given id.
     */
    private AtmosphereConsoleInfoEndpoint newEndpoint(MockEnvironment environment, String exposedId) {
        var endpoint = mock(ExposableWebEndpoint.class);
        when(endpoint.getEndpointId()).thenReturn(EndpointId.of(exposedId));
        when(endpoint.getRootPath()).thenReturn(exposedId);
        WebEndpointsSupplier supplier = () -> List.of(endpoint);

        var context = mock(ApplicationContext.class);
        when(context.getClassLoader()).thenReturn(getClass().getClassLoader());
        when(context.getEnvironment()).thenReturn(environment);
        // Only the WebEndpointsSupplier resolves; every other capability probe
        // (interactions, verifier, checkpoints…) must still report absent.
        when(context.getBeanNamesForType(any(Class.class), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> WebEndpointsSupplier.class.equals(invocation.getArgument(0))
                        ? new String[] { "webEndpointsSupplier" } : new String[0]);
        when(context.getBean(WebEndpointsSupplier.class)).thenReturn(supplier);

        return new AtmosphereConsoleInfoEndpoint(new AtmosphereProperties(), framework(), context);
    }

    private AtmosphereFramework framework() {
        var framework = mock(AtmosphereFramework.class);
        when(framework.getAtmosphereHandlers()).thenReturn(Map.of());
        return framework;
    }
}
