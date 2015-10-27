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

import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

/**
 * Handle {@link org.atmosphere.config.service.Singleton},{@link org.atmosphere.config.service.MeteorService} and
 * {@link org.atmosphere.config.service.AtmosphereHandlerService} processing.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereHandlerServiceInterceptor extends ServiceInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereHandlerServiceInterceptor.class);

    protected void mapAnnotatedService(boolean reMap, String path, AtmosphereRequest request, AtmosphereHandlerWrapper w) {
        synchronized (config.handlers()) {
            if (config.handlers().get(path) == null) {
                // AtmosphereHandlerService
                AtmosphereHandlerService m = w.atmosphereHandler.getClass().getAnnotation(AtmosphereHandlerService.class);
                if (m != null) {
                    try {
                        String targetPath = m.path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            boolean singleton = w.atmosphereHandler.getClass().getAnnotation(Singleton.class) != null;
                            AtmosphereHandler newW = w.atmosphereHandler;

                            if (!singleton) {
                                newW = config.framework().newClassInstance(AtmosphereHandler.class, w.atmosphereHandler.getClass());
                            }

                            request.localAttributes().put(Named.class.getName(), path.substring(targetPath.indexOf("{")));

                            AtmosphereResourceImpl.class.cast(request.resource()).atmosphereHandler(newW);

                            config.framework().addAtmosphereHandler(path, newW,
                                    config.getBroadcasterFactory().lookup(w.broadcaster.getClass(), path, true), w.interceptors);
                            request.setAttribute(FrameworkConfig.NEW_MAPPING, "true");
                        }
                    } catch (Throwable e) {
                        logger.warn("Unable to create AtmosphereHandler", e);
                    }
                }

            }
        }
    }

    @Override
    public String toString() {
        return "@AtmosphereHandlerService Interceptor";
    }
}
