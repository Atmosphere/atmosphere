/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.config.managed;

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.interceptor.InvokationOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle {@link org.atmosphere.config.service.Singleton},{@link org.atmosphere.config.service.MeteorService} and {@link org.atmosphere.config.service.AtmosphereHandlerService}
 * processing.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereHandlerServiceInterceptor extends AtmosphereInterceptorAdapter {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereHandlerServiceInterceptor.class);
    private AtmosphereConfig config;
    private boolean wildcardMapping = false;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        optimizeMapping();
    }

    protected void optimizeMapping() {
        for (String w : config.handlers().keySet()) {
            if (w.contains("{") && w.contains("}")) {
                wildcardMapping = true;
                break;
            }
        }
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        if (!wildcardMapping) return Action.CONTINUE;

        AtmosphereRequest request = r.getRequest();
        AtmosphereHandlerWrapper w =  (AtmosphereHandlerWrapper)
                        r.getRequest().getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER_WRAPPER);
        Broadcaster b = w.broadcaster;

        String path;
        String pathInfo = null;
        try {
            pathInfo = request.getPathInfo();
        } catch (IllegalStateException ex) {
            // http://java.net/jira/browse/GRIZZLY-1301
        }

        if (pathInfo != null) {
            path = request.getServletPath() + pathInfo;
        } else {
            path = request.getServletPath();
        }

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // Remove the Broadcaster with curly braces
        if (b.getID().contains("{")) {
            config.getBroadcasterFactory().remove(b.getID());
        }

        synchronized (config.handlers()) {
            if (config.handlers().get(path) == null) {
                // AtmosphereHandlerService
                if (w.atmosphereHandler.getClass().getAnnotation(AtmosphereHandlerService.class) != null) {
                    try {
                        boolean singleton = w.atmosphereHandler.getClass().getAnnotation(Singleton.class) != null;
                        if (!singleton) {
                            config.framework().addAtmosphereHandler(path, w.atmosphereHandler.getClass().newInstance(), w.interceptors);
                        } else {
                            config.framework().addAtmosphereHandler(path, w.atmosphereHandler, w.interceptors);
                        }
                        request.setAttribute(FrameworkConfig.NEW_MAPPING, "true");
                    } catch (Throwable e) {
                        logger.warn("Unable to create AtmosphereHandler", e);
                    }
                }

            }
        }
        return Action.CONTINUE;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }
}
