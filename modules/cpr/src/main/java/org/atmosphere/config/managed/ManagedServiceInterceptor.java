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

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.AnnotatedProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

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
                    ManagedAnnotation a = managed(ap, request.resource());
                    if (a != null) {
                        String targetPath = a.path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            try {
                                boolean singleton = ap.target().getClass().getAnnotation(Singleton.class) != null;
                                if (!singleton) {
                                    ap = proxyHandler();

                                    final Object o = config.framework().newClassInstance(Object.class, AnnotatedProxy.class.cast(w.atmosphereHandler).target().getClass());
                                    ap.configure(config, o);
                                }

                                request.localAttributes().put(Named.class.getName(), path.substring(targetPath.indexOf("{")));
                                if (ap.pathParams()) {
                                    request.localAttributes().put(PathParam.class.getName(), new String[]{path, targetPath});
                                }

                                AtmosphereResourceImpl.class.cast(request.resource()).atmosphereHandler(ap);

                                config.framework().addAtmosphereHandler(path, ap,
                                        config.getBroadcasterFactory().lookup(a.broadcaster(), path, true), w.interceptors);
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

    protected AnnotatedProxy proxyHandler() throws IllegalAccessException, InstantiationException {
        return config.framework().newClassInstance(AnnotatedProxy.class, ManagedAtmosphereHandler.class);
    }

    protected ManagedAnnotation managed(AnnotatedProxy ap, final AtmosphereResource r){
        final ManagedService a = ap.target().getClass().getAnnotation(ManagedService.class);
        if (a == null) return null;

        return new ManagedAnnotation() {
            @Override
            public String path() {
                return a.path();
            }

            @Override
            public Class<? extends Broadcaster> broadcaster() {
                return r.getBroadcaster().getClass();
            }  };
    }

    protected static interface ManagedAnnotation {

        String path();

        Class<? extends Broadcaster> broadcaster();

    }

    @Override
    public String toString() {
        return "@ManagedService Interceptor";
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        config.properties().remove(Thread.currentThread().getName() + ".PATH");
    }
}
