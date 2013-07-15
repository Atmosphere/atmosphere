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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.config.managed.AnnotationServiceInterceptor;
import org.atmosphere.config.managed.AtmosphereHandlerServiceInterceptor;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.config.managed.MeteorServiceInterceptor;
import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.config.service.AtmosphereService;
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
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that handles the results of an annotation scan. This class contains the logic that maps
 * an annotation type to the corresponding framework setup.
 *
 * @author Stuart Douglas
 * @author Jeanfrancois Arcand
 */
public class AnnotationHandler {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationHandler.class);

    private AnnotationHandler() {
    }

    // TODO: Add annotation -> logicHandler callback
    // TODO: Refactor this class, please!
    public static void handleAnnotation(final AtmosphereFramework framework, final Class<? extends Annotation> annotation, final Class<?> discoveredClass) {
        logger.info("Found Annotation in {} being scanned: {}", discoveredClass, annotation);
        if (AtmosphereHandlerService.class.equals(annotation)) {
            try {
                AtmosphereHandlerService a = discoveredClass.getAnnotation(AtmosphereHandlerService.class);

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
                    l.add(new AtmosphereHandlerServiceInterceptor());
                }

                framework.sessionSupport(a.supportSession());

                AtmosphereHandler handler = (AtmosphereHandler) discoveredClass.newInstance();
                for (String s : a.properties()) {
                    String[] nv = s.split("=");
                    IntrospectionUtils.setProperty(handler, nv[0], nv[1]);
                    IntrospectionUtils.addProperty(handler, nv[0], nv[1]);
                }

                framework.addAtmosphereHandler(a.path(), handler, l);
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (BroadcasterCacheService.class.equals(annotation)) {
            framework.setBroadcasterCacheClassName(discoveredClass.getName());
        } else if (BroadcasterCacheInspectorService.class.equals(annotation)) {
            try {
                framework.addBroadcasterCacheInjector((BroadcasterCacheInspector) discoveredClass.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (MeteorService.class.equals(annotation)) {
            try {
                ReflectorServletProcessor r = new ReflectorServletProcessor();
                r.setServletClassName(discoveredClass.getName());

                Class<Servlet> s = (Class<Servlet>) discoveredClass;
                MeteorService m = s.getAnnotation(MeteorService.class);

                String mapping = m.path();

                atmosphereConfig(m.atmosphereConfig(), framework);
                framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
                filters(m.broadcastFilters(), framework);

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

                if (m.path().contains("{")) {
                    l.add(new MeteorServiceInterceptor());
                }
                framework.addAtmosphereHandler(mapping, r, l);
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (BroadcasterFilterService.class.equals(annotation)) {
            try {
                framework.broadcasterFilters((BroadcastFilter) discoveredClass.newInstance());
            } catch (Exception e) {
                logger.warn("", e);
            }
        } else if (BroadcasterService.class.equals(annotation)) {
            framework.setDefaultBroadcasterClassName(discoveredClass.getName());
        } else if (WebSocketHandlerService.class.equals(annotation)) {
            try {
                Class<WebSocketHandler> s = (Class<WebSocketHandler>) discoveredClass;
                WebSocketHandlerService m = s.getAnnotation(WebSocketHandlerService.class);

                framework.addAtmosphereHandler(m.path(), new AbstractReflectorAtmosphereHandler() {
                    @Override
                    public void onRequest(AtmosphereResource resource) throws IOException {
                    }

                    @Override
                    public void destroy() {
                    }
                }).initWebSocket();

                atmosphereConfig(m.atmosphereConfig(), framework);
                framework.setDefaultBroadcasterClassName(m.broadcaster().getName());
                filters(m.broadcastFilters(), framework);

                interceptors(m.interceptors(), framework);

                WebSocketProcessor p = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework);
                p.registerWebSocketHandler(m.path(), s.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (WebSocketProtocolService.class.equals(annotation)) {
            framework.setWebSocketProtocolClassName(discoveredClass.getName());
        } else if (AtmosphereInterceptorService.class.equals(annotation)) {
            try {
                AtmosphereInterceptor a = (AtmosphereInterceptor) discoveredClass.newInstance();
                framework.interceptor(a);
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (AsyncSupportService.class.equals(annotation)) {
            try {
                framework.setAsyncSupport(new DefaultAsyncSupportResolver(framework.config).newCometSupport(discoveredClass.getName()));
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (AsyncSupportListenerService.class.equals(annotation)) {
            try {
                framework.asyncSupportListener((AsyncSupportListener) discoveredClass.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (BroadcasterFactoryService.class.equals(annotation)) {
            try {
                Class<BroadcasterFactory> bf = (Class<BroadcasterFactory>) discoveredClass;
                framework.setBroadcasterFactory(bf.newInstance());
                framework.configureBroadcasterFactory();
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (BroadcasterListenerService.class.equals(annotation)) {
            try {
                framework.addBroadcasterListener((BroadcasterListener) discoveredClass.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (WebSocketProcessorService.class.equals(annotation)) {
            try {
                framework.setWebsocketProcessorClassName(discoveredClass.getName());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (ManagedService.class.equals(annotation)) {
            try {
                Class<?> aClass = discoveredClass;
                ManagedService a = aClass.getAnnotation(ManagedService.class);
                List<AtmosphereInterceptor> l = new ArrayList<AtmosphereInterceptor>();

                atmosphereConfig(a.atmosphereConfig(), framework);
                framework.setDefaultBroadcasterClassName(a.broadcaster().getName());
                filters(a.broadcastFilters(), framework);

                final Class<? extends AtmosphereResourceEventListener>[] listeners = a.listeners();
                if (listeners.length > 0) {
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

                            @Override
                            public String toString() {
                                return "@ManagedService Event Listeners";
                            }

                        };
                        l.add(ai);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                }

                Object c = aClass.newInstance();
                AtmosphereHandler handler = new ManagedAtmosphereHandler(c);
                Class<?>[] interceptors = a.interceptors();
                for (Class i : interceptors) {
                    try {
                        AtmosphereInterceptor ai;
                        if (AnnotationServiceInterceptor.class.isAssignableFrom(i)) {
                            ai = new AnnotationServiceInterceptor(ManagedAtmosphereHandler.class.cast(handler));
                        } else {
                            ai = (AtmosphereInterceptor) i.newInstance();
                        }
                        l.add(ai);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                }
                framework.addAtmosphereHandler(a.path(), handler, l);
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (AtmosphereService.class.equals(annotation)) {
            try {
                Class<?> aClass = discoveredClass;
                AtmosphereService a = aClass.getAnnotation(AtmosphereService.class);

                atmosphereConfig(a.atmosphereConfig(), framework);
                framework.setDefaultBroadcasterClassName(a.broadcaster().getName());
                filters(a.broadcastFilters(), framework);

                final Class<? extends AtmosphereResourceEventListener>[] listeners = a.listeners();
                if (listeners.length > 0) {
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

                            @Override
                            public String toString() {
                                return "@Atmosphere Managed Event Listeners";
                            }

                        };
                        framework.interceptor(ai);
                    } catch (Throwable e) {
                        logger.warn("", e);
                    }
                }

                interceptors(a.interceptors(), framework);

                framework.setBroadcasterCacheClassName(a.broadcasterCache().getName());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        } else if (EndpoinMapperService.class.equals(annotation)) {
            try {
                framework.endPointMapper((EndpointMapper<?>) discoveredClass.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        }
    }

    private static void interceptors(Class<? extends AtmosphereInterceptor>[] interceptors, AtmosphereFramework framework) {
        for (Class i : interceptors) {
            try {
                framework.interceptor((AtmosphereInterceptor) i.newInstance());
            } catch (Throwable e) {
                logger.warn("", e);
            }
        }
    }

    private static void filters(Class<? extends BroadcastFilter>[] bf, AtmosphereFramework framework) throws IllegalAccessException, InstantiationException {
        for (Class<? extends BroadcastFilter> b : bf) {
            framework.broadcasterFilters(b.newInstance());
        }
    }

    private static void atmosphereConfig(String[] m, AtmosphereFramework framework) {
        for (String s : m) {
            String[] nv = s.split("=");
            framework.addInitParameter(nv[0], nv[1]);
        }
    }
}


