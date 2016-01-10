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
package org.atmosphere.inject;

import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.AnnotatedProxy;
import org.atmosphere.inject.annotation.RequestScoped;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link PathParam} injection support.
 *
 * @author Jeanfrancois Arcand
 */
@RequestScoped({PathParam.class})
public class PathParamIntrospector extends InjectIntrospectorAdapter<String> {
    private final Logger logger = LoggerFactory.getLogger(PathParamIntrospector.class);
    protected final ThreadLocal<String> pathLocal = new ThreadLocal<String>();

    @Override
    public boolean supportedType(Type t) {
        return (t instanceof Class) && String.class.isAssignableFrom((Class) t);
    }

    @Override
    public String injectable(AtmosphereResource r) {
        String named = pathLocal.get();
        String[] paths = (String[]) r.getRequest().getAttribute(PathParam.class.getName());

        if (paths == null || paths.length != 2) {
            AtmosphereFramework.AtmosphereHandlerWrapper w = (AtmosphereFramework.AtmosphereHandlerWrapper)
                    r.getRequest().getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER_WRAPPER);

            if (w != null) {
                if (AnnotatedProxy.class.isAssignableFrom(w.atmosphereHandler.getClass())) {
                    AnnotatedProxy ap = AnnotatedProxy.class.cast(w.atmosphereHandler);
                    if (ap.target().getClass().isAnnotationPresent(ManagedService.class)) {
                        String targetPath = ap.target().getClass().getAnnotation(ManagedService.class).path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            paths = new String[] { Utils.pathInfo(r.getRequest()), targetPath };
                        }
                    }
                }

                if (paths == null || paths.length != 2) {
                    return null;
                }
            }
        }

        /* first, split paths at slashes and map {{parameter names}} to values from pathLocal */
        logger.debug("Path: {}, targetPath: {}", pathLocal, paths[1]);
        String[] inParts = paths[0].split("/");
        String[] outParts = paths[1].split("/");
        Map<String, String> annotatedPathVars = new HashMap<>();
        int len = Math.min(outParts.length, inParts.length);
        for (int i = 0; i < len; i++) {
            String s = outParts[i];
            if (s.startsWith("{") && s.endsWith("}")) {
                /* we remove braces from string and put it to our map and also pathLocal that regex like room: [a-zA-Z][a-zA-Z_0-9]* */
                int end = s.contains(":") ? s.indexOf(":") : s.length() - 1;
                annotatedPathVars.put(s.substring(1, end), inParts[i]);
                logger.debug("Putting PathVar pair: {} -> {}", s.substring(1, s.length() - 1), inParts[i]);
            }
        }
        return annotatedPathVars.get(named);
    }

    @Override
    public void introspectField(Class clazz, Field f) {
        if (f.isAnnotationPresent(PathParam.class)) {
            String name = f.getAnnotation(PathParam.class).value();

            if (name.isEmpty()) {
                name = f.getName();
            }
            pathLocal.set(name);
        }
    }

}
