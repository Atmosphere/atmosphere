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
package org.atmosphere.cpr;

import eu.infomas.annotation.AnnotationDetector;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.config.service.BroadcasterFactoryService;
import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.config.service.EndpoinMapperService;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.config.service.WebSocketProcessorService;
import org.atmosphere.config.service.WebSocketProtocolService;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.ManagedAtmosphereHandler;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link AnnotationProcessor} based on <a href="https://github.com/rmuller/infomas-asl"></a>
 * <p/>
 * TODO: This class needs to refactored.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultAnnotationProcessor implements AnnotationProcessor {

    private org.slf4j.Logger logger = LoggerFactory.getLogger(DefaultAnnotationProcessor.class);
    protected AnnotationDetector detector;

    public DefaultAnnotationProcessor() {
    }

    @Override
    public AnnotationProcessor configure(final AtmosphereFramework framework) {
        final AnnotationDetector.TypeReporter reporter = new AnnotationDetector.TypeReporter() {

            @SuppressWarnings("unchecked")
            @Override
            public Class<? extends Annotation>[] annotations() {
                return new Class[]{
                        AtmosphereHandlerService.class,
                        BroadcasterCacheService.class,
                        BroadcasterFilterService.class,
                        BroadcasterFactoryService.class,
                        BroadcasterService.class,
                        MeteorService.class,
                        WebSocketHandlerService.class,
                        WebSocketProtocolService.class,
                        AtmosphereInterceptorService.class,
                        BroadcasterListenerService.class,
                        AsyncSupportService.class,
                        AsyncSupportListenerService.class,
                        WebSocketProcessorService.class,
                        BroadcasterCacheInspectorService.class,
                        ManagedService.class,
                        EndpoinMapperService.class,
                };
            }

            // TODO: Add annotation -> logicHandler callback
            @Override
            public void reportTypeAnnotation(Class<? extends Annotation> annotation, String className) {
                logger.info("Found Annotation in {} being scanned: {}", className, annotation);
                if (AtmosphereHandlerService.class.equals(annotation)) {
                    try {
                        AtmosphereHandler handler = (AtmosphereHandler) loadClass(className).newInstance();
                        AtmosphereHandlerService a = handler.getClass().getAnnotation(AtmosphereHandlerService.class);

                        framework.setDefaultBroadcasterClassName(a.broadcaster().getName());
                        Class<? extends BroadcastFilter>[] bf = a.broadcastFilters();
                        for (Class<? extends BroadcastFilter> b : bf) {
                            framework.broadcasterFilters().add(b.getName());
                        }

                        for (String s : a.properties()) {
                            String[] nv = s.split("=");
                            IntrospectionUtils.setProperty(handler, nv[0], nv[1]);
                            IntrospectionUtils.addProperty(handler, nv[0], nv[1]);
                        }

                        for (String s : a.atmosphereConfig()) {
                            String[] nv = s.split("=");
                            framework.addInitParameter(nv[0], nv[1]);
                        }

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
                        framework.addAtmosphereHandler(a.path(), handler, l);
                        framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());
                        framework.sessionSupport(a.supportSession());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (BroadcasterCacheService.class.equals(annotation)) {
                    framework.setBroadcasterCacheClassName(className);
                } else if (BroadcasterCacheInspectorService.class.equals(annotation)) {
                    try {
                        framework.addBroadcasterCacheInjector((BroadcasterCacheInspector) loadClass(className).newInstance());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (MeteorService.class.equals(annotation)) {
                    try {
                        ReflectorServletProcessor r = new ReflectorServletProcessor();
                        r.setServletClassName(className);

                        Class<Servlet> s = (Class<Servlet>) loadClass(className);
                        MeteorService m = s.getAnnotation(MeteorService.class);

                        String mapping = m.path();
                        framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
                        Class<? extends BroadcastFilter>[] bf = m.broadcastFilters();
                        for (Class<? extends BroadcastFilter> b : bf) {
                            framework.broadcasterFilters().add(b.getName());
                        }
                        for (String i : m.atmosphereConfig()) {
                            String[] nv = i.split("=");
                            framework.addInitParameter(nv[0], nv[1]);
                        }

                        Class<?>[] interceptors = m.interceptors();
                        List<AtmosphereInterceptor> l = new ArrayList<AtmosphereInterceptor>();
                        for (Class i : interceptors) {
                            try {
                                AtmosphereInterceptor ai = (AtmosphereInterceptor) i.newInstance();
                                l.add(ai);
                            } catch (Throwable e) {
                                logger.warn("", e);
                            }
                        }
                        framework.addAtmosphereHandler(mapping, r, l);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (BroadcasterFilterService.class.equals(annotation)) {
                    framework.broadcasterFilters().add(className);
                } else if (BroadcasterService.class.equals(annotation)) {
                    framework.setDefaultBroadcasterClassName(className);
                } else if (WebSocketHandlerService.class.equals(annotation)) {
                    try {
                        Class<WebSocketHandler> s = (Class<WebSocketHandler>) loadClass(className);
                        WebSocketHandlerService m = s.getAnnotation(WebSocketHandlerService.class);

                        framework.addAtmosphereHandler(m.path(), new AbstractReflectorAtmosphereHandler() {
                            @Override
                            public void onRequest(AtmosphereResource resource) throws IOException {
                            }

                            @Override
                            public void destroy() {
                            }
                        }).initWebSocket();

                        WebSocketProcessor p = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
                        p.registerWebSocketHandler(m.path(), s.newInstance());

                        framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
                        Class<? extends BroadcastFilter>[] bf = m.broadcastFilters();
                        for (Class<? extends BroadcastFilter> b : bf) {
                            framework.broadcasterFilters().add(b.getName());
                        }
                        framework.setBroadcasterCacheClassName(m.broadcasterCache().getName());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (WebSocketProtocolService.class.equals(annotation)) {
                    framework.setWebSocketProtocolClassName(className);
                } else if (AtmosphereInterceptorService.class.equals(annotation)) {
                    try {
                        AtmosphereInterceptor a = (AtmosphereInterceptor) loadClass(className).newInstance();
                        framework.interceptor(a);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (AsyncSupportService.class.equals(annotation)) {
                    try {
                        framework.setAsyncSupport(new DefaultAsyncSupportResolver(framework.config).newCometSupport(className));
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (AsyncSupportListenerService.class.equals(annotation)) {
                    try {
                        framework.asyncSupportListener((AsyncSupportListener) loadClass(className).newInstance());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (BroadcasterFactoryService.class.equals(annotation)) {
                    try {
                        Class<BroadcasterFactory> bf = (Class<BroadcasterFactory>) loadClass(className);
                        framework.setBroadcasterFactory(bf.newInstance());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (BroadcasterListenerService.class.equals(annotation)) {
                    try {
                        framework.addBroadcasterListener((BroadcasterListener) loadClass(className).newInstance());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (WebSocketProcessorService.class.equals(annotation)) {
                    try {
                        framework.setWebsocketProcessorClassName(className);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (ManagedService.class.equals(annotation)) {
                    try {

                        AtmosphereHandler handler;
                        try {
                            handler = (AtmosphereHandler) loadClass(className).newInstance();
                        } catch (Throwable t) {
                            Object c = loadClass(className).newInstance();
                            handler = new ManagedAtmosphereHandler(c);
                        }
                        managed(handler, annotation);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                } else if (EndpoinMapperService.class.equals(annotation)) {
                    try {
                        framework.endPointMapper((EndpointMapper<?>) loadClass(className).newInstance());
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                }
            }

            void managed(AtmosphereHandler handler, Class<? extends Annotation> annotation) {
                    ManagedService a = handler.getClass().getAnnotation(ManagedService.class);
                    // POJO
                    if (a == null) {
                        a = ManagedAtmosphereHandler.class.cast(handler).object().getClass().getAnnotation(ManagedService.class);
                    }

                    framework.setDefaultBroadcasterClassName(a.broadcaster().getName());

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

                    final Class<? extends AtmosphereResourceEventListener>[] listeners = a.listeners();
                    try {
                        AtmosphereInterceptor ai = new AtmosphereInterceptor() {

                            @Override
                            public void configure(AtmosphereConfig config) {
                            }

                            @Override
                            public Action inspect(AtmosphereResource r) {
                                for (Class<? extends AtmosphereResourceEventListener> l : listeners) {
                                    try {
                                        r.addEventListener(l.newInstance());
                                    } catch (Throwable e) {
                                        logger.warn("", e);
                                    }
                                }
                                return Action.CONTINUE;
                            }

                            @Override
                            public void postInspect(AtmosphereResource r) {

                            }
                        };
                        l.add(ai);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }

                    framework.addAtmosphereHandler(a.path(), handler, l);
                    framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());
                }

        };
        detector = new AnnotationDetector(reporter);
        return this;
    }

    @Override
    public AnnotationProcessor scan(File rootDir) throws IOException {
        detector.detect(rootDir);
        return this;
    }

    @Override
    public AnnotationProcessor scan(String packageName) throws IOException {
        logger.trace("Scanning @Service annotations in {}", packageName);
        detector.detect(packageName);
        return this;
    }

    private Class<?> loadClass(String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            return getClass().getClassLoader().loadClass(className);
        }
    }
}
