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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.util.Version;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

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
                    .build();
        }

        Health.Builder builder = Health.up()
                .withDetail("version", Version.getRawVersion());

        BroadcasterFactory broadcasterFactory = framework.getBroadcasterFactory();
        if (broadcasterFactory != null) {
            builder.withDetail("broadcasters", broadcasterFactory.lookupAll().size());
        }

        builder.withDetail("connections", framework.atmosphereFactory().findAll().size());

        return builder.build();
    }
}
