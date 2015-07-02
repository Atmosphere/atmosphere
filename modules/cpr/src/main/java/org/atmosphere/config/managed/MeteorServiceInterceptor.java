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
package org.atmosphere.config.managed;

import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.servlet.Servlet;

/**
 * Handle {@link org.atmosphere.config.service.Singleton},{@link org.atmosphere.config.service.MeteorService}
 * processing.
 *
 * @author Jeanfrancois Arcand
 */
public class MeteorServiceInterceptor extends ServiceInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(MeteorServiceInterceptor.class);

    protected void mapAnnotatedService(boolean reMap, String path, AtmosphereRequest request, AtmosphereFramework.AtmosphereHandlerWrapper w) {
        synchronized (config.handlers()) {
            if (config.handlers().get(path) == null) {
                // MeteorService
                if (ReflectorServletProcessor.class.isAssignableFrom(w.atmosphereHandler.getClass())) {
                    ReflectorServletProcessor r = ReflectorServletProcessor.class.cast(w.atmosphereHandler);
                    Servlet s = r.getServlet();
                    MeteorService m = s.getClass().getAnnotation(MeteorService.class);
                    if (m != null) {
                        String targetPath = m.path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            try {
                                boolean singleton = s.getClass().getAnnotation(Singleton.class) != null;
                                if (!singleton) {
                                    r = config.framework().newClassInstance(ReflectorServletProcessor.class, ReflectorServletProcessor.class);
                                    r.setServlet(config.framework().newClassInstance(Servlet.class, s.getClass()));
                                    r.init(config);
                                }

                                request.localAttributes().put(Named.class.getName(), path.substring(targetPath.indexOf("{")));

                                AtmosphereResourceImpl.class.cast(request.resource()).atmosphereHandler(r);

                                config.framework().addAtmosphereHandler(path, r,
                                        config.getBroadcasterFactory().lookup(w.broadcaster.getClass(), path, true), w.interceptors);
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
        return "@MeteorService Interceptor";
    }
}
