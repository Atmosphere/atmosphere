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
package org.atmosphere.quarkus.runtime;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.metrics.AtmosphereHealth;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * MicroProfile {@link HealthCheck} that wraps {@link AtmosphereHealth} and
 * publishes its snapshot to the {@code /q/health/live} and
 * {@code /q/health/ready} endpoints exposed by {@code quarkus-smallrye-health}.
 *
 * <p>Reports {@code UP} when the framework is alive (not destroyed) and the
 * framework reference is available, {@code DOWN} otherwise. The status
 * payload mirrors the data
 * {@code org.atmosphere.spring.boot.AtmosphereHealthIndicator} publishes via
 * Spring Boot Actuator: version, handlers, broadcasters, connections,
 * interceptors.</p>
 *
 * <p>The framework reference is resolved lazily through
 * {@link LazyAtmosphereConfigurator#getFramework()} — this side-steps an
 * ambiguous CDI injection on {@link AtmosphereFramework} (Atmosphere ships
 * a producer in {@code AtmosphereProducers}; injecting via Arc would
 * collide). The lookup also guards correctly for the boot window where the
 * Quarkus servlet hasn't completed {@code init()} yet — {@link #call()}
 * reports {@code DOWN} with a {@code "reason=initializing"} detail rather
 * than throwing.</p>
 *
 * <p>This bean is registered as an {@code AdditionalBeanBuildItem} +
 * {@code HealthBuildItem} by {@code AtmosphereProcessor.registerHealthCheck}
 * only when {@code quarkus-smallrye-health} is on the classpath (capability
 * gated). On a classpath without smallrye-health the
 * {@code @Liveness}/{@code @Readiness} qualifiers are unresolved and the
 * bean is never instantiated.</p>
 */
@Liveness
@Readiness
@ApplicationScoped
public class AtmosphereHealthCheck implements HealthCheck {

    /**
     * Health check name exposed under the {@code checks[].name} field of
     * the {@code /q/health/*} JSON payload.
     */
    public static final String CHECK_NAME = "atmosphere";

    @Override
    public HealthCheckResponse call() {
        AtmosphereFramework framework = LazyAtmosphereConfigurator.getFramework();
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(CHECK_NAME);
        if (framework == null) {
            return builder.down()
                    .withData("reason", "initializing")
                    .build();
        }
        if (framework.isDestroyed()) {
            return builder.down()
                    .withData("reason", "destroyed")
                    .build();
        }
        builder.up();
        Map<String, Object> snapshot = new AtmosphereHealth(framework).check();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            // MicroProfile HealthCheckResponseBuilder only accepts String / long / boolean.
            if (value instanceof Number n) {
                builder.withData(key, n.longValue());
            } else if (value instanceof Boolean b) {
                builder.withData(key, b);
            } else {
                builder.withData(key, value.toString());
            }
        }
        return builder.build();
    }
}
