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
package org.atmosphere.samples.springboot.lowlevel;

import java.time.Duration;
import java.time.Instant;

import org.atmosphere.config.service.AtmosphereFrameworkListenerService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereFrameworkListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework-level lifecycle. {@code @AtmosphereFrameworkListenerService} is the only hook
 * that brackets the whole framework rather than a connection — useful for asserting that
 * startup actually completed rather than reporting configuration intent.
 */
@AtmosphereFrameworkListenerService
public class FrameworkUptime implements AtmosphereFrameworkListener {

    private static final Logger logger = LoggerFactory.getLogger(FrameworkUptime.class);

    private static volatile Instant startedAt;

    /** Null until {@code onPostInit} has actually run — confirmed state, not intent. */
    public static Instant startedAt() {
        return startedAt;
    }

    public static Duration uptime() {
        Instant s = startedAt;
        return s == null ? Duration.ZERO : Duration.between(s, Instant.now());
    }

    @Override
    public void onPreInit(AtmosphereFramework f) {
        // Intentionally empty: uptime starts when init COMPLETES, not when it begins.
    }

    @Override
    public void onPostInit(AtmosphereFramework f) {
        startedAt = Instant.now();
        logger.info("Atmosphere framework ready");
    }

    @Override
    public void onPreDestroy(AtmosphereFramework f) {
        // Nothing to release here — startedAt is reset in onPostDestroy.
    }

    @Override
    public void onPostDestroy(AtmosphereFramework f) {
        startedAt = null;
        logger.info("Atmosphere framework destroyed");
    }
}
