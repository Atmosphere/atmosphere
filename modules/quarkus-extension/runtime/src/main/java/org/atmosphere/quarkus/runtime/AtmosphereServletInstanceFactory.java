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
package org.atmosphere.quarkus.runtime;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.InstanceHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AtmosphereServletInstanceFactory implements InstanceFactory<QuarkusAtmosphereServlet> {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServletInstanceFactory.class);

    private final Map<String, List<String>> annotationClassNames;

    public AtmosphereServletInstanceFactory(Map<String, List<String>> annotationClassNames) {
        this.annotationClassNames = annotationClassNames;
        logger.debug("InstanceFactory created with {} annotation entries", annotationClassNames.size());
    }

    @Override
    public InstanceHandle<QuarkusAtmosphereServlet> createInstance() throws InstantiationException {
        QuarkusAtmosphereServlet servlet = new QuarkusAtmosphereServlet();

        // The ObjectFactory is set via init param "org.atmosphere.cpr.objectFactory" in
        // AtmosphereProcessor, which ensures it is applied at the correct point during
        // framework.init() -> configureObjectFactory(). Setting it programmatically here
        // does not survive the init() lifecycle.

        Map<Class<? extends Annotation>, Set<Class<?>>> resolvedMap = resolveAnnotationMap();
        servlet.setAnnotationMap(resolvedMap);

        logger.debug("Created QuarkusAtmosphereServlet with {} annotation entries", resolvedMap.size());

        return new InstanceHandle<>() {
            @Override
            public QuarkusAtmosphereServlet getInstance() {
                return servlet;
            }

            @Override
            public void release() {
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<Class<? extends Annotation>, Set<Class<?>>> resolveAnnotationMap() {
        Map<Class<? extends Annotation>, Set<Class<?>>> resolved = new HashMap<>();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        for (Map.Entry<String, List<String>> entry : annotationClassNames.entrySet()) {
            try {
                Class<? extends Annotation> annotationClass =
                        (Class<? extends Annotation>) tccl.loadClass(entry.getKey());
                Set<Class<?>> classes = new HashSet<>();
                for (String className : entry.getValue()) {
                    try {
                        classes.add(tccl.loadClass(className));
                    } catch (ClassNotFoundException e) {
                        logger.warn("Could not load annotated class: {}", className, e);
                    }
                }
                if (!classes.isEmpty()) {
                    resolved.put(annotationClass, classes);
                }
            } catch (ClassNotFoundException e) {
                logger.warn("Could not load annotation class: {}", entry.getKey(), e);
            }
        }

        return resolved;
    }
}
