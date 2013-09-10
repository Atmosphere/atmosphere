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
import org.atmosphere.config.managed.AtmosphereHandlerServiceInterceptor;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.util.IntrospectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(AtmosphereHandlerService.class)
public class AtmosphereHandlerServiceProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereHandlerServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<?> annotatedClass) {
        try {
            AtmosphereHandlerService a = annotatedClass.getAnnotation(AtmosphereHandlerService.class);

            atmosphereConfig(a.atmosphereConfig(), framework);
            framework.setDefaultBroadcasterClassName(a.broadcaster().getName());
            filters(a.broadcastFilters(), framework);

            Class<?>[] interceptors = a.interceptors();
            List<AtmosphereInterceptor> l = new ArrayList<AtmosphereInterceptor>();
            for (Class i : interceptors) {
                try {
                    AtmosphereInterceptor ai = (AtmosphereInterceptor) i.newInstance();
                    l.add(ai);
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }

            if (a.path().contains("{")) {
                framework.interceptors().add(new AtmosphereHandlerServiceInterceptor());
            }

            AtmosphereInterceptor aa = listeners(a.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            framework.sessionSupport(a.supportSession());

            AtmosphereHandler handler = (AtmosphereHandler) annotatedClass.newInstance();
            for (String s : a.properties()) {
                String[] nv = s.split("=");
                IntrospectionUtils.setProperty(handler, nv[0], nv[1]);
                IntrospectionUtils.addProperty(handler, nv[0], nv[1]);
            }

            framework.addAtmosphereHandler(a.path(), handler, l);
            framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
