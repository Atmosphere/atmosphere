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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CORS support with origin allowlist.
 *
 * <p>Configure allowed origins via
 * {@value ApplicationConfig#CORS_ALLOWED_ORIGINS} (comma-separated).
 * When not configured, the interceptor echoes the request {@code Origin}
 * header for backward compatibility but logs a warning.</p>
 *
 * @author Janusz Sobolewski
 */
public class CorsInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CorsInterceptor.class);

    private boolean enableAccessControl = true;
    private Set<String> allowedOrigins;
    private volatile boolean warnedOpenCors;

    @Override
    public void configure(AtmosphereConfig config) {
        String ac = config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);
        if (ac != null) {
            enableAccessControl = !Boolean.parseBoolean(ac);
        }

        String origins = config.getInitParameter(ApplicationConfig.CORS_ALLOWED_ORIGINS);
        if (origins != null && !origins.isBlank()) {
            allowedOrigins = Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            logger.info("CORS allowedOrigins: {}", allowedOrigins);
        }
    }

    @Override
    public Action inspect(AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) {
            return Action.CONTINUE;
        }

        if (!enableAccessControl) {
            return Action.CONTINUE;
        }

        var req = r.getRequest();
        var res = r.getResponse();

        String exposeHeaders = "X-Atmosphere-tracking-id, " + HeaderConfig.X_HEARTBEAT_SERVER;
        String origin = req.getHeader("Origin");
        if (origin != null && res.getHeader("Access-Control-Allow-Origin") == null) {
            if (isOriginAllowed(origin)) {
                res.addHeader("Access-Control-Allow-Origin", origin);
                res.addHeader("Access-Control-Expose-Headers", exposeHeaders);
                res.setHeader("Access-Control-Allow-Credentials", "true");
            }
        }

        if ("OPTIONS".equals(req.getMethod())) {
            res.setHeader("Access-Control-Allow-Methods", "OPTIONS, GET, POST");
            res.setHeader("Access-Control-Allow-Headers",
                    "Origin, Content-Type, AuthToken, X-Atmosphere-Framework, X-Requested-With, "
                            + exposeHeaders
                            + ", X-Atmosphere-Transport, X-Atmosphere-TrackMessageSize, X-atmo-protocol");
            res.setHeader("Access-Control-Max-Age", "-1");

            return Action.SKIP_ATMOSPHEREHANDLER;
        }

        return Action.CONTINUE;
    }

    /**
     * Returns {@code true} if the origin is in the allowlist, or if no
     * allowlist is configured (backward-compatible open CORS with warning).
     */
    private boolean isOriginAllowed(String origin) {
        if (allowedOrigins != null) {
            return allowedOrigins.contains(origin);
        }
        // No allowlist configured — backward-compatible echo with warning
        if (!warnedOpenCors) {
            warnedOpenCors = true;
            logger.warn("CorsInterceptor: no allowedOrigins configured — echoing all Origin headers. "
                    + "Set '{}' for production.", ApplicationConfig.CORS_ALLOWED_ORIGINS);
        }
        return true;
    }

    public boolean enableAccessControl() {
        return enableAccessControl;
    }

    public CorsInterceptor enableAccessControl(boolean enableAccessControl) {
        this.enableAccessControl = enableAccessControl;
        return this;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.FIRST_BEFORE_DEFAULT;
    }

    @Override
    public String toString() {
        return "CORS Interceptor Support";
    }
}
