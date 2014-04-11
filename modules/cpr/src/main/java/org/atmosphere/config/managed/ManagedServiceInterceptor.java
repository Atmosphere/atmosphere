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
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.AnnotatedProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

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
                                    AnnotatedProxy h = proxyHandler();

                                    final Object o = config.framework().newClassInstance(Object.class, ap.target().getClass());
                                    h.configure(config, o);

                                    if (h.pathParams()) {
                                        prepareForPathInjection(path, targetPath, o);
                                    }

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

    protected AnnotatedProxy proxyHandler() throws IllegalAccessException, InstantiationException {
        return config.framework().newClassInstance(AnnotatedProxy.class, ManagedAtmosphereHandler.class);
    }

    protected ManagedAnnotation managed(AnnotatedProxy ap, AtmosphereResource r){
        final ManagedService a = ap.target().getClass().getAnnotation(ManagedService.class);
        if (a == null) return null;

        return new ManagedAnnotation(){
            @Override
            public String path() {
                return a.path();
            }

            @Override
            public Class<? extends Broadcaster> broadcaster() {
                return a.broadcaster();
            }
        };
    }

    protected void prepareForPathInjection(String path, String targetPath, Object o) {
        /* begin @PathVariable annotations processing */

        /* first, split paths at slashes and map {{parameter names}} to values from path */
        logger.debug("Path: {}, targetPath: {}", path, targetPath);
        String[] inParts = path.split("/");
        String[] outParts = targetPath.split("/");
        Map<String, String> annotatedPathVars = new HashMap<String, String>();
        int len = Math.min(outParts.length, inParts.length);
        for (int i = 0; i < len; i++) {
            String s = outParts[i];
            if (s.startsWith("{") && s.endsWith("}")) {
                /* we remove braces from string and put it to our map and also path that regex like room: [a-zA-Z][a-zA-Z_0-9]* */
                int end = s.contains(":") ? s.indexOf(":"): s.length() - 1;
                annotatedPathVars.put(s.substring(1, end), inParts[i]);
                logger.debug("Putting PathVar pair: {} -> {}", s.substring(1, s.length() - 1), inParts[i]);
            }
        }
        injectPathParams(o, annotatedPathVars);
    }

    protected void injectPathParams(Object o, Map<String, String> annotatedPathVars){
        /* now look for appropriate annotations and fill the variables accordingly */
        for (Field field : o.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(PathParam.class)) {
                PathParam annotation = field.getAnnotation(PathParam.class);
                String name = annotation.value();
                if (name.isEmpty()) {
                    name = field.getName();
                }
                if (annotatedPathVars.containsKey(name)) {
                    try {
                        logger.debug("Annotating field {}", name);
                        field.setAccessible(true);
                        field.set(o, annotatedPathVars.get(name));
                    } catch (Exception e) {
                        logger.error("Error processing @PathVariable annotation", e);
                    }
                } else {
                    logger.error("No path marker found for PathVariable {}, class {}", field.getName(), o.getClass());
                }
            }
        }
        /* end @PathVariable annotations processing */
    }

    protected static interface ManagedAnnotation{

        String path();

        Class<? extends Broadcaster> broadcaster();

    }

    @Override
    public String toString() {
        return "@ManagedService Interceptor";
    }
}
