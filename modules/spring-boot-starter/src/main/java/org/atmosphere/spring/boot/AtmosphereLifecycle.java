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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Participates in Spring Boot's graceful shutdown lifecycle to ensure the
 * {@link AtmosphereFramework} is properly destroyed before the servlet
 * container shuts down.
 *
 * <p>This runs in the {@link SmartLifecycle#DEFAULT_PHASE} so it stops
 * before the web server. During stop, it calls {@link AtmosphereFramework#destroy()}
 * which disconnects all resources and cleans up thread pools.</p>
 */
public class AtmosphereLifecycle implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereLifecycle.class);

    private final AtmosphereFramework framework;
    private volatile boolean running = true;

    public AtmosphereLifecycle(AtmosphereFramework framework) {
        this.framework = framework;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (running && !framework.isDestroyed()) {
            logger.info("Graceful shutdown: destroying Atmosphere framework");
            framework.destroy();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Stop before the web server (which runs at DEFAULT_PHASE)
        return SmartLifecycle.DEFAULT_PHASE - 1;
    }
}
