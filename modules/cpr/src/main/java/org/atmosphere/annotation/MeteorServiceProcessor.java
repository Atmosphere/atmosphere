/*
 * Copyright 2013 Jeanfrancois Arcand
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
import java.util.ArrayList;
import java.util.List;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(MeteorService.class)
public class MeteorServiceProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(MeteorServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<?> annotatedClass) {
        try {
            ReflectorServletProcessor r = new ReflectorServletProcessor();
            r.setServletClassName(annotatedClass.getName());
            List<AtmosphereInterceptor> l = new ArrayList<AtmosphereInterceptor>();

            Class<Servlet> s = (Class<Servlet>) annotatedClass;
            MeteorService m = s.getAnnotation(MeteorService.class);

            String mapping = m.path();

            atmosphereConfig(m.atmosphereConfig(), framework);
            framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
            filters(m.broadcastFilters(), framework);

            AtmosphereInterceptor aa = listeners(m.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            Class<?>[] interceptors = m.interceptors();
            for (Class i : interceptors) {
                try {
                    AtmosphereInterceptor ai = (AtmosphereInterceptor) i.newInstance();
                    l.add(ai);
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }

            if (m.path().contains("{")) {
                framework.interceptors().add(new MeteorServiceInterceptor());
            }
            framework.addAtmosphereHandler(mapping, r, l);
            framework.setBroadcasterCacheClassName(m.broadcasterCache().getName());
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
