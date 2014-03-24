/*
 * Copyright 2014 Jeanfrancois Arcand
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
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.AnnotatedProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle {@link Singleton} for {@link ManagedService} processing.
 *
 * @author Jeanfrancois Arcand
 */
public class ManagedServiceInterceptor extends ServiceInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(ManagedServiceInterceptor.class);

    protected void mapAnnotatedService(boolean reMap, String path, AtmosphereRequest request, AtmosphereFramework.AtmosphereHandlerWrapper w) {
        synchronized (config.handlers()) {
            if (config.handlers().get(path) == null) {
                // ManagedService
                if (AnnotatedProxy.class.isAssignableFrom(w.atmosphereHandler.getClass())) {
                    AnnotatedProxy ap = AnnotatedProxy.class.cast(w.atmosphereHandler);
                    ManagedService a = ap.target().getClass().getAnnotation(ManagedService.class);
                    if (a != null) {
                        String targetPath = a.path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            try {
                                boolean singleton = ap.target().getClass().getAnnotation(Singleton.class) != null;
                                if (!singleton) {
                                    ManagedAtmosphereHandler h = config.framework().newClassInstance(ManagedAtmosphereHandler.class, ManagedAtmosphereHandler.class);
                                    h.configure(config, config.framework().newClassInstance(Object.class, ap.target().getClass()));
                                    config.framework().addAtmosphereHandler(path, h,
                                            config.getBroadcasterFactory().lookup(a.broadcaster(), path, true), w.interceptors);
                                } else {
                                    config.framework().addAtmosphereHandler(path, w.atmosphereHandler,
                                            config.getBroadcasterFactory().lookup(a.broadcaster(), path, true), w.interceptors);
                                }
                                request.setAttribute(FrameworkConfig.NEW_MAPPING, "true");
                            } catch (Throwable e) {
                                logger.warn("Unable to create AtmosphereHandler", e);
                            }
                        }
                    }
                }
            } else if (reMap) {
                request.setAttribute(FrameworkConfig.NEW_MAPPING, "true");
            }
        }
    }

    @Override
    public String toString() {
        return "@ManagedService Interceptor";
    }
}
