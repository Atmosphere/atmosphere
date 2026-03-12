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
import org.atmosphere.config.service.RoomService;
import org.atmosphere.config.service.UUIDProviderService;
import org.atmosphere.config.service.WebSocketFactoryService;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.config.service.WebSocketProcessorService;
import org.atmosphere.config.service.WebSocketProtocolService;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Single source of truth for all Atmosphere annotation types and their processor mappings.
 *
 * <p>All modules that need the list of core Atmosphere annotations should reference this
 * class rather than maintaining their own copy. The one exception is
 * {@link AnnotationScanningServletContainerInitializer} whose {@code @HandlesTypes}
 * annotation requires compile-time class literals and cannot delegate to a runtime list.</p>
 *
 * @author Jeanfrancois Arcand
 */
public final class AtmosphereAnnotations {

    private AtmosphereAnnotations() {
    }

    private static final List<Class<? extends Annotation>> CORE_ANNOTATIONS = List.of(
            AtmosphereHandlerService.class,
            BroadcasterCacheService.class,
            BroadcasterFilterService.class,
            BroadcasterFactoryService.class,
            BroadcasterService.class,
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
            UUIDProviderService.class,
            RoomService.class
    );

    /**
     * Returns the unmodifiable list of all core Atmosphere annotation types.
     *
     * @return the core annotations
     */
    public static List<Class<? extends Annotation>> coreAnnotations() {
        return CORE_ANNOTATIONS;
    }

    /**
     * Returns the fully-qualified class names of all core Atmosphere annotations.
     * Useful for modules that work with string-based annotation names (e.g. Jandex {@code DotName}).
     *
     * @return the core annotation names
     */
    public static List<String> coreAnnotationNames() {
        return CORE_ANNOTATIONS.stream()
                .map(Class::getName)
                .toList();
    }
}
