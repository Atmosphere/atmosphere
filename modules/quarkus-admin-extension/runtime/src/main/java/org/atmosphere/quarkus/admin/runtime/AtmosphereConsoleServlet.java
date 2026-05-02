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
package org.atmosphere.quarkus.admin.runtime;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the bundled Atmosphere chat Console (Vue/Vite SPA) from the
 * classpath at {@code /atmosphere/console/*}.
 *
 * <p>Why this exists: in the Quarkus servlet pipeline the
 * {@link org.atmosphere.cpr.AtmosphereServlet} is registered at
 * {@code /atmosphere/*} and answers every request under that prefix —
 * including {@code /atmosphere/console/*}, which it does not have a
 * handler for, so it returns 404 with {@code X-Atmosphere-error: No
 * AtmosphereHandler maps request for ...}. Quarkus' static-resource
 * dispatcher does not fall through under a servlet's mapping the way
 * Spring Boot's {@code ResourceHttpRequestHandler} does, so the SPA
 * never gets served. Registering this servlet with the more specific
 * {@code /atmosphere/console/*} mapping wins by Servlet-spec URL pattern
 * specificity (longest path-prefix match), letting us keep the
 * {@code /atmosphere/console/} URL convention shared with the Spring
 * Boot starter.</p>
 *
 * <p>The implementation streams classpath resources directly. It does
 * NOT cache, MIME-sniff aggressively, or support range requests — the
 * Console bundle is a tiny ~190KB SPA loaded once. If a future bundle
 * grows beyond that, switch to Quarkus' Vert.x static-resources route
 * with a higher priority than the servlet container.</p>
 */
public class AtmosphereConsoleServlet extends HttpServlet {

    private static final String CLASSPATH_ROOT = "/META-INF/resources/atmosphere/console";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        String pathInfo = req.getPathInfo();
        String relative = (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo))
                ? "/index.html"
                : pathInfo;

        // Strict allow-list of characters — pathInfo from the container is
        // already URL-decoded but we still defensively reject path traversal.
        if (relative.contains("..") || relative.contains("//")) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String resource = CLASSPATH_ROOT + relative;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            resp.setContentType(contentTypeFor(relative));
            // Long cache for hashed assets; short for the index entrypoint.
            if (relative.startsWith("/assets/")) {
                resp.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else {
                resp.setHeader("Cache-Control", "no-cache");
            }
            in.transferTo(resp.getOutputStream());
        }
    }

    private static String contentTypeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html" -> "text/html; charset=UTF-8";
            case "js", "mjs" -> "application/javascript; charset=UTF-8";
            case "css" -> "text/css; charset=UTF-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "json" -> "application/json; charset=UTF-8";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "map" -> "application/json; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }
}
