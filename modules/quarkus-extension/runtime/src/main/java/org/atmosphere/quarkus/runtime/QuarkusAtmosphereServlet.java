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
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.DefaultAnnotationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuarkusAtmosphereServlet extends AtmosphereServlet {

    private static final Logger logger = LoggerFactory.getLogger(QuarkusAtmosphereServlet.class);

    private Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap;

    public void setAnnotationMap(Map<Class<? extends Annotation>, Set<Class<?>>> annotationMap) {
        this.annotationMap = annotationMap;
    }

    @Override
    public void init(ServletConfig sc) throws ServletException {
        logger.info("QuarkusAtmosphereServlet.init() starting");

        if (annotationMap != null) {
            logger.debug("Setting Atmosphere annotation map on ServletContext with {} entries",
                    annotationMap.size());
            sc.getServletContext().setAttribute(
                    DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE, annotationMap);
        }
        super.init(sc);

        // Make the framework available to the WebSocket endpoint configurator.
        // The JSR-356 endpoints were registered during deployment (before init),
        // so the configurator needs a way to obtain the framework lazily.
        // Uses setFramework() which also signals the CountDownLatch, unblocking
        // any WebSocket upgrade requests that arrived before init completed.
        LazyAtmosphereConfigurator.setFramework(framework());
        logger.info("QuarkusAtmosphereServlet.init() completed, framework available for WebSocket");
    }
}
