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

import java.time.Duration;
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
     * The heartbeat interval. Accepts ISO-8601 duration or Quarkus shorthand
     * ({@code 30s}, {@code 5m}, {@code 1h}). Converted to seconds internally.
     */
    Optional<Duration> heartbeatInterval();

    /**
     * Additional Atmosphere init parameters passed to the servlet.
     */
    Map<String, String> initParams();

    /**
     * Optional subtitle shown by the bundled Atmosphere Console. When blank,
     * the {@code /api/console/info} servlet picks a mode-aware default
     * ({@code "Multi-client broadcast chat"} for {@code @ManagedService}
     * endpoints, {@code "Runtime: <name>"} otherwise).
     */
    Optional<String> consoleSubtitle();

    /**
     * Optional explicit endpoint the bundled Atmosphere Console connects to.
     * When blank, the {@code /api/console/info} servlet auto-detects via the
     * registered handler map (prefer {@code /atmosphere/agent/*} over generic
     * paths, fall back to the first {@code /atmosphere/*} that is not
     * {@code /atmosphere/admin/*}).
     */
    Optional<String> consoleEndpoint();

    /**
     * Whether to enable the bounded-memory {@link org.atmosphere.cache.BoundedMemoryCache}
     * and {@link org.atmosphere.interceptor.MessageAckInterceptor}. Mirrors the Spring
     * Boot starter's {@code atmosphere.cache.enabled} property; when {@code true} the
     * deployment processor registers {@link org.atmosphere.cache.BoundedMemoryCache} as
     * the default broadcaster cache via the {@code broadcaster-cache-class} init param.
     */
    @WithDefault("false")
    boolean cacheEnabled();

    /**
     * WebTransport over HTTP/3 configuration.
     *
     * @return the WebTransport sub-configuration block
     */
    WebTransport webTransport();

    /**
     * WebTransport over HTTP/3 configuration block. Mirrors the Spring Boot
     * starter's {@code atmosphere.web-transport.*} keys.
     */
    interface WebTransport {

        /**
         * Whether to start the Netty HTTP/3 sidecar on application startup.
         * Defaults to {@code false}; users opting in must also pull in the
         * {@code atmosphere-webtransport-reactor-netty} module.
         *
         * @return {@code true} if the sidecar should be started
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * UDP port for the HTTP/3 sidecar. Default {@code 4443}.
         *
         * @return the configured UDP port
         */
        @WithDefault("4443")
        int port();
    }

    /**
     * Durable sessions configuration.
     *
     * @return the durable-sessions sub-configuration block
     */
    DurableSessions durableSessions();

    /**
     * Durable sessions sub-configuration. Mirrors the Spring Boot starter's
     * {@code atmosphere.durable-sessions.*} keys; the actual runtime
     * behaviour is exercised by {@link org.atmosphere.session.DurableSessionInterceptor}
     * and the SPI's pluggable {@code SessionStore} CDI beans.
     */
    interface DurableSessions {

        /**
         * Whether to install the {@link org.atmosphere.session.DurableSessionInterceptor}
         * on startup. Defaults to {@code false}; users opting in pick up the
         * default {@code InMemorySessionStore} unless they ship a pluggable
         * {@code SessionStore} CDI bean.
         *
         * @return {@code true} if the interceptor should be installed
         */
        @WithDefault("false")
        boolean enabled();
    }
}
