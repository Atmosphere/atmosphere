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

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.AnnotatedProxy;
import org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor;
import org.atmosphere.interceptor.InvokationOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Handle {@link Singleton} for {@link ManagedService}
 * processing.
 *
 * @author Jeanfrancois Arcand
 */
public class ManagedServiceInterceptor extends BroadcastOnPostAtmosphereInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(ManagedServiceInterceptor.class);
    private ManagedAtmosphereHandler proxy;
    private AtmosphereConfig config;
    private boolean wildcardMapping = false;

    public ManagedServiceInterceptor(ManagedAtmosphereHandler proxy) {
        this.proxy = proxy;
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        optimizeMapping();
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        if (!wildcardMapping) return Action.CONTINUE;

        mapAnnotatedService(r.getRequest(), (AtmosphereHandlerWrapper)
                r.getRequest().getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER_WRAPPER));

        return Action.CONTINUE;
    }

    protected void optimizeMapping() {
        for (String w : config.handlers().keySet()) {
            if (w.contains("{") && w.contains("}")) {
                wildcardMapping = true;
                break;
            }
        }
    }

    /**
     * Inspect the request and its mapped {@link AtmosphereHandler} to determine if the '{}' was used when defined the
     * annotation's path value. It will create a new {@link AtmosphereHandler} in case {} is detected .
     *
     * @param request
     * @param w
     * @return
     */
    protected void mapAnnotatedService(AtmosphereRequest request, AtmosphereHandlerWrapper w) {
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
                // ManagedService
                if (AnnotatedProxy.class.isAssignableFrom(w.atmosphereHandler.getClass())) {
                    AnnotatedProxy ap = AnnotatedProxy.class.cast(w.atmosphereHandler);
                    if (ap.target().getClass().getAnnotation(ManagedService.class) != null) {
                        String targetPath = ap.target().getClass().getAnnotation(ManagedService.class).path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            try {
                                synchronized (config.handlers()) {

                                    boolean singleton = ap.target().getClass().getAnnotation(Singleton.class) != null;
                                    if (!singleton) {
                                        ManagedAtmosphereHandler h = (ManagedAtmosphereHandler) w.atmosphereHandler.getClass().getConstructor(Object.class)
                                                .newInstance(ap.target().getClass().newInstance());

                                        // Quite dangerous
                                        w.interceptors.set(0, new ManagedServiceInterceptor(h));

                                        config.framework().addAtmosphereHandler(path, h, w.interceptors);
                                    } else {
                                        config.framework().addAtmosphereHandler(path, w.atmosphereHandler, w.interceptors);
                                    }
                                }
                                request.setAttribute(FrameworkConfig.NEW_MAPPING, "true");
                            } catch (Throwable e) {
                                logger.warn("Unable to create AtmosphereHandler", e);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        if (proxy != null && r.getRequest().getMethod().equalsIgnoreCase("POST")) {
            StringBuilder b = read(r);
            if (b.length() > 0) {
                Object o = null;
                try {
                    o = proxy.invoke(r, b.toString());
                } catch (IOException e) {
                    logger.error("", e);
                }
                if (o != null) {
                    r.getBroadcaster().broadcast(o);
                }
            } else {
                logger.warn("{} received an empty body", ManagedServiceInterceptor.class.getSimpleName());
            }
        }
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.AFTER_DEFAULT;
    }

    @Override
    public String toString(){
        return "@ManagedService Interceptor";
    }
}
