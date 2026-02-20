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
package org.atmosphere.cpr;

import org.atmosphere.cache.DefaultBroadcasterCache;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.Version;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.atmosphere.cpr.ApplicationConfig.ANALYTICS;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_WAIT_TIME;

/**
 * Diagnostic logging and version-check utilities for the Atmosphere framework.
 * All methods are read-only — they never modify framework state.
 */
final class FrameworkDiagnostics {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereFramework.class);

    private FrameworkDiagnostics() {}

    static void info(AtmosphereFramework fwk) {
        var config = fwk.getAtmosphereConfig();
        var servletConfig = config.getServletConfig();
        var broadcasterSetup = fwk.broadcasterSetup;
        var handlerRegistry = fwk.getHandlerRegistry();
        var webSocketConfig = fwk.getWebSocketConfig();

        if (logger.isTraceEnabled()) {
            Enumeration<String> e = servletConfig.getInitParameterNames();
            logger.trace("Configured init-params");
            String n;
            while (e.hasMoreElements()) {
                n = e.nextElement();
                logger.trace("\t{} = {}", n, servletConfig.getInitParameter(n));
            }
        }

        logger.info("Using EndpointMapper {}", handlerRegistry.endPointMapper().getClass());
        for (String i : broadcasterSetup.broadcasterFilters()) {
            logger.info("Using BroadcastFilter: {}", i);
        }

        if (broadcasterSetup.broadcasterCacheClassName() == null || DefaultBroadcasterCache.class.getName().equals(broadcasterSetup.broadcasterCacheClassName())) {
            logger.warn("No BroadcasterCache configured. Broadcasted message between client reconnection will be LOST. " +
                    "It is recommended to configure the {}", UUIDBroadcasterCache.class.getName());
        } else {
            logger.info("Using BroadcasterCache: {}", broadcasterSetup.broadcasterCacheClassName());
        }

        String s = config.getInitParameter(BROADCASTER_WAIT_TIME);

        logger.info("Default Broadcaster Class: {}", broadcasterSetup.broadcasterClassName());
        logger.info("Broadcaster Shared List Resources: {}", config.getInitParameter(BROADCASTER_SHAREABLE_LISTENERS, false));
        logger.info("Broadcaster Polling Wait Time {}", s == null ? DefaultBroadcaster.POLLING_DEFAULT : s);
        logger.info("Shared ExecutorService supported: {}", fwk.isShareExecutorServices());

        ExecutorService executorService = ExecutorsFactory.getMessageDispatcher(config, Broadcaster.ROOT_MASTER);
        if (executorService != null) {
            if (executorService instanceof ThreadPoolExecutor tpe) {
                long max = tpe.getMaximumPoolSize();
                logger.info("Messaging Thread Pool Size: {}",
                        tpe.getMaximumPoolSize() == 2147483647 ? "Unlimited" : max);
            } else {
                logger.info("Messaging ExecutorService Pool Size unavailable - Not instance of ThreadPoolExecutor");
            }
        }

        executorService = ExecutorsFactory.getAsyncOperationExecutor(config, Broadcaster.ROOT_MASTER);
        if (executorService != null) {
            if (executorService instanceof ThreadPoolExecutor tpe) {
                logger.info("Async I/O Thread Pool Size: {}",
                        tpe.getMaximumPoolSize());
            } else {
                logger.info("Async I/O ExecutorService Pool Size unavailable - Not instance of ThreadPoolExecutor");
            }
        }
        logger.info("Using BroadcasterFactory: {}", broadcasterSetup.broadcasterFactory().getClass().getName());
        logger.info("Using AtmosphereResurceFactory: {}", broadcasterSetup.arFactory().getClass().getName());
        logger.info("Using WebSocketProcessor: {}", webSocketConfig.getProcessorClassName());
        if (broadcasterSetup.defaultSerializerClassName() != null && !broadcasterSetup.defaultSerializerClassName().isEmpty()) {
            logger.info("Using Serializer: {}", broadcasterSetup.defaultSerializerClassName());
        }

        WebSocketProcessor wp = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(fwk);
        boolean b = false;
        if (wp instanceof DefaultWebSocketProcessor dwp) {
            b = dwp.invokeInterceptors();
        }
        logger.info("Invoke AtmosphereInterceptor on WebSocket message {}", b);
        logger.info("HttpSession supported: {}", config.isSupportSession());

        logger.info("Atmosphere is using {} for dependency injection and object creation", fwk.objectFactory());
        logger.info("Atmosphere is using async support: {} running under container: {}",
                fwk.getAsyncSupport().getClass().getName(), fwk.getAsyncSupport().getContainerName());
        logger.info("Atmosphere Framework {} started.", Version.getRawVersion());

        logger.info("\n\n\tFor Atmosphere Framework Commercial Support, visit \n\t{} " +
                "or send an email to {}\n", "http://www.async-io.org/", "support@async-io.org");

        if (logger.isTraceEnabled()) {
            for (Entry<String, AtmosphereHandlerWrapper> e : handlerRegistry.handlers().entrySet()) {
                logger.trace("\nConfigured AtmosphereHandler {}\n", e.getKey());
                logger.trace("{}", e.getValue());
            }
        }
    }

    static void analytics(AtmosphereConfig config) {
        if (!config.getInitParameter(ANALYTICS, true)) return;

        var t = new Thread(() -> {
            try {
                var currentVersion = Version.getRawVersion();
                if (currentVersion.contains("SNAPSHOT")) return;

                logger.debug("Checking for Atmosphere updates via GitHub API");
                var url = URI.create("https://api.github.com/repos/Atmosphere/atmosphere/releases/latest").toURL();
                var conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "Atmosphere/" + currentVersion);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(true);

                try {
                    if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                    var body = new String(conn.getInputStream().readAllBytes());
                    // Minimal JSON parsing — extract "tag_name":"atmosphere-X.Y.Z"
                    int idx = body.indexOf("\"tag_name\"");
                    if (idx < 0) return;
                    int start = body.indexOf('"', idx + 10) + 1;
                    int end = body.indexOf('"', start);
                    var tag = body.substring(start, end);
                    var latestVersion = tag.startsWith("atmosphere-") ? tag.substring(11) : tag;

                    if (latestVersion.compareTo(currentVersion) > 0
                            && !latestVersion.toLowerCase().contains("rc")
                            && !latestVersion.toLowerCase().contains("beta")) {
                        logger.info("\n\n\tAtmosphere {} is available (you are running {})"
                                        + "\n\thttps://github.com/Atmosphere/atmosphere/releases/tag/{}",
                                latestVersion, currentVersion, tag);
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Throwable e) {
                // Best-effort version check — never fail startup
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
