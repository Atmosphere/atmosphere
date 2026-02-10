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
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Atmosphere Framework configuration.
 */
@ConfigMapping(prefix = "quarkus.atmosphere")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface AtmosphereConfig {

    /**
     * The URL pattern for the Atmosphere servlet mapping.
     */
    @WithDefault("/atmosphere/*")
    String servletPath();

    /**
     * Comma-separated list of packages to scan for Atmosphere annotations.
     */
    Optional<String> packages();

    /**
     * The load-on-startup order for the Atmosphere servlet.
     * Must be greater than 0 for Quarkus to initialize the servlet at startup
     * (Quarkus skips {@code setLoadOnStartup} when the value is {@code <= 0}).
     */
    @WithDefault("1")
    int loadOnStartup();

    /**
     * Whether to enable HTTP session support.
     */
    @WithDefault("false")
    boolean sessionSupport();

    /**
     * The fully qualified class name of the Broadcaster implementation.
     */
    Optional<String> broadcasterClass();

    /**
     * The fully qualified class name of the BroadcasterCache implementation.
     */
    Optional<String> broadcasterCacheClass();

    /**
     * Whether to enable WebSocket support.
     */
    Optional<Boolean> websocketSupport();

    /**
     * The heartbeat interval in seconds.
     */
    Optional<Integer> heartbeatIntervalInSeconds();

    /**
     * Additional Atmosphere init parameters passed to the servlet.
     */
    Map<String, String> initParams();
}
