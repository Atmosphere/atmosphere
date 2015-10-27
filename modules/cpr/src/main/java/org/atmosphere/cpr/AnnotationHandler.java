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
package org.atmosphere.cpr;

import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class that handles the results of an annotation scan. This class contains the logic that maps
 * an annotation type to the corresponding framework setup.
 *
 * @author Stuart Douglas
 * @author Jeanfrancois Arcand
 */
public class AnnotationHandler {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationHandler.class);

    private final Map<Class<? extends Annotation>, Class<? extends Processor>> annotations = new HashMap<Class<? extends Annotation>, Class<? extends Processor>>();
    private final Map<Class<? extends Processor>, Processor> processors = new HashMap<Class<? extends Processor>, Processor>();

    public AnnotationHandler() {
    }

    public AnnotationHandler flushCoreAnnotations(Set<Class<?>> classes){
        List<Class<? extends Annotation>> l = new ArrayList<Class<? extends Annotation>>();
        for (Map.Entry<Class<? extends Annotation>, Class<? extends Processor>> e : annotations.entrySet()) {
            if (e.getValue().getPackage().getName().equals("org.atmosphere.annotation") && classes.contains(e.getValue())) {
                l.add(e.getKey());
            }
        }

        for (Class<? extends Annotation> c : l) {
            annotations.remove(c);
        }
        return this;
    }

    public Class<? extends Processor> handleProcessor(Class<?> clazz) {
        if (Processor.class.isAssignableFrom(clazz)) {
            Class<Processor> p = (Class<Processor>) clazz;
            if (logger.isTraceEnabled()) {
                logger.trace("Processor {} associated with {}", p, p.getAnnotation(AtmosphereAnnotation.class).value());
            }
            annotations.put(p.getAnnotation(AtmosphereAnnotation.class).value(), p);
            return p;
        }
        return null;
    }

    public Class<? extends Annotation>[] handledClass() {
        Collection<Class<? extends Annotation>> c = annotations.keySet();
        return c.toArray(new Class[0]);
    }

    public AnnotationHandler handleAnnotation(final AtmosphereFramework framework, final Class<? extends Annotation> annotation, final Class<?> discoveredClass) {
        logger.info("Found Annotation in {} being scanned: {}", discoveredClass, annotation);
        framework.annotationScanned(true);
        Class<? extends Processor> a = annotations.get(annotation);

        if (a != null) {
            Processor p = processors.get(a);
            if (p == null) {
                try {
                    p = framework.newClassInstance(Processor.class, a);
                } catch (Exception e) {
                    logger.warn("Unable to create Processor {}", p);
                }
                processors.put(a, p);
            }
            p.handle(framework, discoveredClass);
            logger.trace("Annotation {} handled by {}", annotation, p.getClass().getName());
        } else {
            logger.trace("Annotation {} unhandled", annotation);
        }
        return this;
    }

    public void destroy() {
        annotations.clear();
        processors.clear();
    }
}


