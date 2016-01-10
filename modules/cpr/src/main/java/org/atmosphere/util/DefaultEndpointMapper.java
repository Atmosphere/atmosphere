/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.util.uri.UriTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of the {@link EndpointMapper} used by the {@link org.atmosphere.cpr.AsynchronousProcessor}
 * and {@link org.atmosphere.websocket.DefaultWebSocketProcessor}
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultEndpointMapper<U> implements EndpointMapper<U> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultEndpointMapper.class);

    public DefaultEndpointMapper() {
    }

    protected U match(String path, Map<String, U> handlers) {
        U handler = handlers.get(path);

        if (handler == null) {
            final Map<String, String> m = new HashMap<String, String>();
            for (Map.Entry<String, U> e : handlers.entrySet()) {
                UriTemplate t = null;
                try {
                    t = new UriTemplate(e.getKey());
                    logger.trace("Trying to map {} to {}", t, path);
                    if (t.match(path, m)) {
                        handler = e.getValue();
                        logger.trace("Mapped {} to {}", t, e.getValue());
                        break;
                    }
                } finally {
                    if (t != null) t.destroy();
                }
            }
        }
        return handler;
    }

    public String computePath(AtmosphereRequest req) {
        return Utils.pathInfo(req);
    }

    @Override
    public void configure(AtmosphereConfig config) {
    }

    @Override
    public U map(AtmosphereRequest req, Map<String, U> handlers) {
        String path = computePath(req);

        U handler = map(path, handlers);
        if (handler == null) {
            // (2) First, try exact match
            handler = map(path + (path.endsWith("/") ? "all" : "/all"), handlers);

            if (handler == null) {
                // (3) Wildcard
                handler = map(path + "*", handlers);

                // (4) try without a path
                if (handler == null) {
                    String p = path.lastIndexOf("/") <= 0 ? "/" : path.substring(0, path.lastIndexOf("/"));
                    while (p.length() > 0) {
                        handler = map(p, handlers);

                        // (3.1) Try path wildcard
                        if (handler != null) {
                            break;
                        }
                        p = p.substring(0, p.lastIndexOf("/"));
                    }
                }

                // Glassfish 3.1.2 issue .. BEUUUURRRRKKKKKK!!
                if (handler == null && req.getContextPath().length() < path.length()) {
                    path = path.substring(req.getContextPath().length());
                    handler = map(path, handlers);
                }
            }
        }
        req.setAttribute(FrameworkConfig.MAPPED_PATH, path);
        return handler;
    }

    @Override
    public U map(String path, Map<String, U> handlers) {

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        U handler = match(path, handlers);
        if (handler == null) {
            // (2) First, try exact match
            handler = match(path + (path.endsWith("/") ? "all" : "/all"), handlers);

            if (handler == null) {
                // (3) Wildcard
                handler = match(path + "*", handlers);

                // (4) try without a path
                if (handler == null) {
                    String p = path.lastIndexOf("/") <= 0 ? "/" : path.substring(0, path.lastIndexOf("/"));
                    while (p.length() > 0 && p.indexOf("/") != -1) {
                        handler = match(p, handlers);

                        // (3.1) Try path wildcard
                        if (handler != null) {
                            break;
                        }
                        p = p.substring(0, p.lastIndexOf("/"));
                    }
                }
            }
        }
        return handler;
    }
}
