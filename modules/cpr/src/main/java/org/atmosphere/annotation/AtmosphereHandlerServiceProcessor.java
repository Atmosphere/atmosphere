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
import org.atmosphere.config.managed.AtmosphereHandlerServiceInterceptor;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.util.IntrospectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedList;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.broadcaster;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.listeners;

@AtmosphereAnnotation(AtmosphereHandlerService.class)
public class AtmosphereHandlerServiceProcessor implements Processor<AtmosphereHandler> {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereHandlerServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<AtmosphereHandler> annotatedClass) {
        try {
            AtmosphereHandlerService a = annotatedClass.getAnnotation(AtmosphereHandlerService.class);

            atmosphereConfig(a.atmosphereConfig(), framework);
            filters(a.broadcastFilters(), framework);

            Class<?>[] interceptors = a.interceptors();
            LinkedList<AtmosphereInterceptor> l = new LinkedList<AtmosphereInterceptor>();
            for (Class i : interceptors) {
                try {
                    AtmosphereInterceptor ai = (AtmosphereInterceptor) framework.newClassInstance(AtmosphereHandler.class, i);
                    l.add(ai);
                } catch (Throwable e) {
                    logger.warn("", e);
                }
            }

            AtmosphereInterceptor aa = listeners(a.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            if (a.path().contains("{")) {
                l.addFirst(framework.newClassInstance(AtmosphereInterceptor.class, AtmosphereHandlerServiceInterceptor.class));
            }

            framework.sessionSupport(a.supportSession());

            AtmosphereHandler handler = framework.newClassInstance(AtmosphereHandler.class, annotatedClass);
            for (String s : a.properties()) {
                String[] nv = s.split("=");
                IntrospectionUtils.setProperty(handler, nv[0], nv[1]);
                IntrospectionUtils.addProperty(handler, nv[0], nv[1]);
            }

            AnnotationUtil.interceptorsForHandler(framework, Arrays.asList(a.interceptors()), l);
            framework.addAtmosphereHandler(a.path(), handler, broadcaster(framework, a.broadcaster(), a.path()), l);
            framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
