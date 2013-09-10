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
import org.atmosphere.config.service.AtmosphereService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServletProcessor;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.LinkedList;

import static org.atmosphere.annotation.AnnotationUtil.atmosphereConfig;
import static org.atmosphere.annotation.AnnotationUtil.filters;
import static org.atmosphere.annotation.AnnotationUtil.interceptors;
import static org.atmosphere.annotation.AnnotationUtil.listeners;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERERESOURCE_INTERCEPTOR_METHOD;

@AtmosphereAnnotation(AtmosphereService.class)
public class AtmosphereServiceProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<?> annotatedClass) {
        try {
            Class<?> aClass = annotatedClass;
            AtmosphereService a = aClass.getAnnotation(AtmosphereService.class);

            atmosphereConfig(a.atmosphereConfig(), framework);
            framework.setDefaultBroadcasterClassName(a.broadcaster().getName());
            filters(a.broadcastFilters(), framework);

            LinkedList<AtmosphereInterceptor> l = new LinkedList<AtmosphereInterceptor>();
            AtmosphereInterceptor aa = listeners(a.listeners(), framework);
            if (aa != null) {
                l.add(aa);
            }

            if (!a.servlet().isEmpty()) {
                final ReflectorServletProcessor r = new ReflectorServletProcessor();
                r.setServletClassName(a.servlet());

                String mapping = a.path();

                Class<?>[] interceptors = a.interceptors();
                for (Class i : interceptors) {
                    try {
                        AtmosphereInterceptor ai = (AtmosphereInterceptor) i.newInstance();
                        l.add(ai);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                }

                if (!a.dispatch()) {
                    AtmosphereHandler proxy = new AtmosphereServletProcessor() {

                        private String method = "GET";

                        @Override
                        public void onRequest(AtmosphereResource resource) throws IOException {
                            if (!resource.getRequest().getMethod().equalsIgnoreCase(method)) {
                                r.onRequest(resource);
                            }
                        }

                        @Override
                        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                            r.onStateChange(event);
                        }

                        @Override
                        public void destroy() {
                            r.destroy();
                        }

                        @Override
                        public void init(ServletConfig sc) throws ServletException {
                            String s = sc.getInitParameter(ATMOSPHERERESOURCE_INTERCEPTOR_METHOD);
                            if (s != null) {
                                method = s;
                            }
                            r.init(sc);
                        }
                    };
                    framework.addAtmosphereHandler(mapping, proxy, l);
                } else {
                    framework.addAtmosphereHandler(mapping, r, l);
                }
            } else {
                interceptors(a.interceptors(), framework);
            }

            framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
