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

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.service.AsyncSupportListenerService;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.config.service.AtmosphereFrameworkListenerService;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.config.service.AtmosphereResourceFactoryService;
import org.atmosphere.config.service.AtmosphereResourceListenerService;
import org.atmosphere.config.service.AtmosphereService;
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.config.service.BroadcasterCacheListenerService;
import org.atmosphere.config.service.BroadcasterCacheService;
import org.atmosphere.config.service.BroadcasterFactoryService;
import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.config.service.BroadcasterListenerService;
import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.config.service.EndpointMapperService;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.UUIDProviderService;
import org.atmosphere.config.service.WebSocketFactoryService;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.config.service.WebSocketProcessorService;
import org.atmosphere.config.service.WebSocketProtocolService;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A ServletContainerInitializer that scans for annotations, and places them in a map keyed by annotation type in the
 * servlet context.
 *
 * NOTE: Any new Atmosphere's annotation must be hardcoded here.
 *
 * @author Stuart Douglas
 */
@HandlesTypes({
        AtmosphereHandlerService.class,
        BroadcasterCacheService.class,
        BroadcasterFilterService.class,
        BroadcasterFactoryService.class,
        BroadcasterService.class,
        MeteorService.class,
        WebSocketFactoryService.class,
        WebSocketHandlerService.class,
        WebSocketProtocolService.class,
        AtmosphereInterceptorService.class,
        BroadcasterListenerService.class,
        AsyncSupportService.class,
        AsyncSupportListenerService.class,
        WebSocketProcessorService.class,
        BroadcasterCacheInspectorService.class,
        ManagedService.class,
        AtmosphereService.class,
        EndpointMapperService.class,
        BroadcasterCacheListenerService.class,
        AtmosphereAnnotation.class,
        AtmosphereResourceFactoryService.class,
        AtmosphereFrameworkListenerService.class,
        AtmosphereResourceListenerService.class,
        UUIDProviderService.class
})
public class AnnotationScanningServletContainerInitializer implements ServletContainerInitializer {

    @Override
    public void onStartup(final Set<Class<?>> classes, final ServletContext servletContext) throws ServletException {
        final Map<Class<? extends Annotation>, Set<Class<?>>> classesByAnnotation = new HashMap<Class<? extends Annotation>, Set<Class<?>>>();
        if (classes != null) {
            for(final Class<?> clazz : classes) {
                for(Annotation annotation : clazz.getAnnotations()) {
                    Set<Class<?>> classSet = classesByAnnotation.get(annotation.annotationType());
                    if(classSet == null) {
                        classesByAnnotation.put(annotation.annotationType(), classSet = new HashSet<Class<?>>());
                    }
                    classSet.add(clazz);
                }
            }
        }
        servletContext.setAttribute(DefaultAnnotationProcessor.ANNOTATION_ATTRIBUTE, classesByAnnotation);
    }
}
