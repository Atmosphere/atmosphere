/*
 * Copyright 2012 Jeanfrancois Arcand
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

import eu.infomas.annotation.AnnotationDetector;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.config.service.WebSocketProtocolService;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.util.IntrospectionUtils;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;

/**
 * An {@link AnnotationProcessor} based on <a href="https://github.com/rmuller/infomas-asl"></a>
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultAnnotationProcessor implements AnnotationProcessor {

    private AtmosphereFramework framework;
    private org.slf4j.Logger logger = LoggerFactory.getLogger(DefaultAnnotationProcessor.class);

    public DefaultAnnotationProcessor() {
    }

    @Override
    public AnnotationProcessor configure(AtmosphereFramework framework) {
        this.framework = framework;
        return this;
    }

    @Override
    public AnnotationProcessor scan(File rootDir) throws IOException {
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        final AnnotationDetector.TypeReporter reporter = new AnnotationDetector.TypeReporter() {

            @SuppressWarnings("unchecked")
            @Override
            public Class<? extends Annotation>[] annotations() {
                return new Class[]{
                        AtmosphereHandlerService.class,
                        BroadcasterCacheService.class,
                        BroadcasterFilterService.class,
                        BroadcasterService.class,
                        MeteorService.class,
                        WebSocketHandlerService.class,
                        WebSocketProtocolService.class,
                        AtmosphereInterceptorService.class,
                        AsyncSupportService.class
                };
            }

            @Override
            public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                logger.info("Annotation in {} being scanned: {}", className, annotation);
                if (AtmosphereHandlerService.class.equals(annotation)) {
                    try {
                        AtmosphereHandler handler = (AtmosphereHandler) cl.loadClass(className).newInstance();
                        AtmosphereHandlerService a = handler.getClass().getAnnotation(AtmosphereHandlerService.class);

                        framework.addAtmosphereHandler(a.path(), handler);
                        framework.setDefaultBroadcasterClassName(a.broadcasterClassName());

                        for (String s : a.properties()) {
                            String[] nv = s.split(",");
                            IntrospectionUtils.setProperty(handler, nv[0], nv[1]);
                            IntrospectionUtils.addProperty(handler, nv[0], nv[1]);
                        }

                        for (String s : a.atmosphereConfig()) {
                            String[] nv = s.split(",");
                            framework.addInitParameter(nv[0], nv[1]);
                        }

                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (BroadcasterCacheService.class.equals(annotation)) {
                    framework.setBroadcasterCacheClassName(className);
                } else if (MeteorService.class.equals(annotation)) {
                    try {
                        ReflectorServletProcessor r = new ReflectorServletProcessor();
                        r.setServletClassName(className);

                        Class<Servlet> s = (Class<Servlet>) cl.loadClass(className);
                        String mapping = s.getAnnotation(MeteorService.class).value();
                        framework.addAtmosphereHandler(mapping, r);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (BroadcasterFilterService.class.equals(annotation)) {
                    framework.broadcasterFilters().add(className);
                } else if (BroadcasterService.class.equals(annotation)) {
                    framework.setDefaultBroadcasterClassName(className);
                } else if (WebSocketHandlerService.class.equals(annotation)) {
                    framework.setWebSocketProtocolClassName(className);
                } else if (WebSocketProtocolService.class.equals(annotation)) {
                    framework.setWebSocketProtocolClassName(className);
                } else if (AtmosphereInterceptorService.class.equals(annotation)) {
                    try {
                        framework.interceptor((AtmosphereInterceptor) cl.loadClass(className).newInstance());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (AsyncSupportService.class.equals(annotation)) {
                    try {
                        framework.setAsyncSupport(new DefaultAsyncSupportResolver(framework.config).newCometSupport(className));
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                }
            }
        };
        logger.info("Scanning @Service annotations in {}", rootDir.getAbsolutePath());
        final AnnotationDetector cf = new AnnotationDetector(reporter);
        cf.detect(rootDir);
        return this;
    }
}
