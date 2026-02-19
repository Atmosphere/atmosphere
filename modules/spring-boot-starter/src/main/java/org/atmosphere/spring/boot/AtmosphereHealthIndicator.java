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

import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.util.Version;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Health indicator for the Atmosphere framework. Reports framework status,
 * version, connection counts, and async transport details.
 *
 * <p>This indicator reports DOWN only when the framework has been destroyed.
 * Zero registered handlers is reported as UP with a warning detail, which
 * is expected in GraalVM native images where annotation scanning is limited.
 * It can be included in Kubernetes health groups:</p>
 * <pre>
 * management.endpoint.health.group.readiness.include=readinessState,atmosphere
 * </pre>
 */
public class AtmosphereHealthIndicator implements HealthIndicator {

    private final AtmosphereFramework framework;

    public AtmosphereHealthIndicator(AtmosphereFramework framework) {
        this.framework = framework;
    }

    @Override
    public Health health() {
        if (framework.isDestroyed()) {
            return Health.down()
                    .withDetail("version", Version.getRawVersion())
                    .withDetail("reason", "destroyed")
                    .build();
        }

        int handlers = framework.getAtmosphereHandlers().size();

        var builder = Health.up()
                .withDetail("version", Version.getRawVersion())
                .withDetail("handlers", handlers);

        if (handlers == 0) {
            builder.withDetail("warning", "no handlers registered");
        }

        BroadcasterFactory broadcasterFactory = framework.getBroadcasterFactory();
        if (broadcasterFactory != null) {
            builder.withDetail("broadcasters", broadcasterFactory.lookupAll().size());
        }

        builder.withDetail("connections", framework.atmosphereFactory().findAll().size());

        AsyncSupport<?> asyncSupport = framework.getAsyncSupport();
        if (asyncSupport != null) {
            builder.withDetail("asyncSupport", asyncSupport.getClass().getSimpleName());
        }

        return builder.build();
    }
}
