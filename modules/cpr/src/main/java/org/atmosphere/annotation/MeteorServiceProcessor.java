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
package org.atmosphere.annotation;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.managed.MeteorServiceInterceptor;
import org.atmosphere.config.service.MeteorService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.util.Arrays;
import java.util.LinkedList;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.broadcaster;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(MeteorService.class)
public class MeteorServiceProcessor implements Processor<Servlet> {

    private static final Logger logger = LoggerFactory.getLogger(MeteorServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Servlet> annotatedClass) {
        try {
            ReflectorServletProcessor r = framework.newClassInstance(ReflectorServletProcessor.class, ReflectorServletProcessor.class);
            r.setServletClassName(annotatedClass.getName());
            LinkedList<AtmosphereInterceptor> l = new LinkedList<AtmosphereInterceptor>();

            MeteorService m = annotatedClass.getAnnotation(MeteorService.class);
            framework.setBroadcasterCacheClassName(m.broadcasterCache().getName());

            String mapping = m.path();

            atmosphereConfig(m.atmosphereConfig(), framework);
            filters(m.broadcastFilters(), framework);

            AtmosphereInterceptor aa = listeners(m.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            AnnotationUtil.interceptorsForHandler(framework, Arrays.asList(m.interceptors()), l);

            if (m.path().contains("{")) {
                l.addFirst(framework.newClassInstance(AtmosphereInterceptor.class, MeteorServiceInterceptor.class));
            }
            framework.addAtmosphereHandler(mapping, r, broadcaster(framework, m.broadcaster(), m.path()), l);
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }

}
