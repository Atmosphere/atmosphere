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

import java.util.List;

/**
 * Single source of truth for all Atmosphere types that require reflective access
 * at runtime (GraalVM native image, Spring AOT, Quarkus native build).
 *
 * <p>Both the Spring Boot starter ({@code AtmosphereRuntimeHints}) and the Quarkus
 * deployment processor ({@code AtmosphereProcessor}) should reference these lists
 * instead of maintaining their own copies.</p>
 *
 * @author Jeanfrancois Arcand
 * @see AtmosphereAnnotations
 */
public final class AtmosphereReflectiveTypes {

    private AtmosphereReflectiveTypes() {
    }

    private static final String ANNOTATION_PKG = "org.atmosphere.annotation.";

    /**
     * Core Atmosphere framework types that are instantiated reflectively at runtime
     * via {@code IOUtils.loadClass}, {@code getDeclaredConstructor}, or ServiceLoader.
     * This covers framework infrastructure, injectable SPI implementations,
     * async support, default interceptors, WebSocket internals, and the
     * annotation processor.
     */
    private static final List<String> CORE_TYPES = List.of(
            // Framework infrastructure
            "org.atmosphere.cpr.AtmosphereFramework",
            "org.atmosphere.cpr.DefaultBroadcaster",
            "org.atmosphere.cpr.DefaultBroadcasterFactory",
            "org.atmosphere.cpr.DefaultAtmosphereResourceFactory",
            "org.atmosphere.cpr.DefaultMetaBroadcaster",
            "org.atmosphere.cpr.DefaultAtmosphereResourceSessionFactory",

            // Core classes instantiated via newClassInstance / getDeclaredConstructor
            "org.atmosphere.cpr.AtmosphereResourceImpl",
            "org.atmosphere.cpr.AtmosphereRequestImpl",
            "org.atmosphere.cpr.AtmosphereResponseImpl",
            "org.atmosphere.config.managed.ManagedAtmosphereHandler",
            "org.atmosphere.config.managed.AtmosphereHandlerServiceInterceptor",
            "org.atmosphere.cpr.DefaultAtmosphereObjectFactory",

            // Injectable SPI implementations (loaded via ServiceLoader)
            "org.atmosphere.inject.InjectableObjectFactory",
            "org.atmosphere.inject.AtmosphereProducers",
            "org.atmosphere.inject.AtmosphereConfigInjectable",
            "org.atmosphere.inject.AtmosphereFrameworkInjectable",
            "org.atmosphere.inject.AtmosphereResourceFactoryInjectable",
            "org.atmosphere.inject.AtmosphereResourceSessionFactoryInjectable",
            "org.atmosphere.inject.BroadcasterFactoryInjectable",
            "org.atmosphere.inject.MetaBroadcasterInjectable",
            "org.atmosphere.inject.WebSocketFactoryInjectable",
            "org.atmosphere.inject.PostConstructIntrospector",
            "org.atmosphere.inject.BroadcasterIntrospector",
            "org.atmosphere.inject.AtmosphereResourceIntrospector",
            "org.atmosphere.inject.AtmosphereRequestIntrospector",
            "org.atmosphere.inject.AtmosphereResponseIntrospector",
            "org.atmosphere.inject.AtmosphereResourceEventIntrospector",
            "org.atmosphere.inject.PathParamIntrospector",

            // AsyncSupport implementations (resolved by DefaultAsyncSupportResolver)
            "org.atmosphere.container.JSR356AsyncSupport",
            "org.atmosphere.container.Servlet30CometSupport",

            // WebSocket internals
            "org.atmosphere.websocket.DefaultWebSocketProcessor",
            "org.atmosphere.websocket.DefaultWebSocketFactory",
            "org.atmosphere.websocket.protocol.SimpleHttpProtocol",
            "org.atmosphere.container.JSR356Endpoint",

            // Default interceptors (loaded by name via IOUtils.loadClass)
            "org.atmosphere.interceptor.CorsInterceptor",
            "org.atmosphere.interceptor.CacheHeadersInterceptor",
            "org.atmosphere.interceptor.PaddingAtmosphereInterceptor",
            "org.atmosphere.interceptor.AndroidAtmosphereInterceptor",
            "org.atmosphere.interceptor.HeartbeatInterceptor",
            "org.atmosphere.interceptor.SSEAtmosphereInterceptor",
            "org.atmosphere.interceptor.JavaScriptProtocol",
            "org.atmosphere.interceptor.WebSocketMessageSuspendInterceptor",
            "org.atmosphere.interceptor.OnDisconnectInterceptor",
            "org.atmosphere.interceptor.IdleResourceInterceptor",
            "org.atmosphere.interceptor.SuspendTrackerInterceptor",

            // Annotation processor
            "org.atmosphere.cpr.DefaultAnnotationProcessor"
    );

    /**
     * Annotation processor classes instantiated reflectively by {@code AnnotationHandler}.
     * Each processor handles a specific Atmosphere annotation at runtime.
     */
    private static final List<String> ANNOTATION_PROCESSORS = List.of(
            ANNOTATION_PKG + "AsyncSupportListenerServiceProcessor",
            ANNOTATION_PKG + "AsyncSupportServiceProcessor",
            ANNOTATION_PKG + "AtmosphereFrameworkServiceProcessor",
            ANNOTATION_PKG + "AtmosphereHandlerServiceProcessor",
            ANNOTATION_PKG + "AtmosphereInterceptorServiceProcessor",
            ANNOTATION_PKG + "AtmosphereResourceFactoryServiceProcessor",
            ANNOTATION_PKG + "AtmosphereResourceListenerServiceProcessor",
            ANNOTATION_PKG + "AtmosphereServiceProcessor",
            ANNOTATION_PKG + "BroadcastFilterServiceProcessor",
            ANNOTATION_PKG + "BroadcasterCacheInspectorServiceProcessor",
            ANNOTATION_PKG + "BroadcasterCacheListenererviceProcessor",
            ANNOTATION_PKG + "BroadcasterCacheServiceProcessor",
            ANNOTATION_PKG + "BroadcasterFactoryServiceProcessor",
            ANNOTATION_PKG + "BroadcasterListenerServiceProcessor",
            ANNOTATION_PKG + "BroadcasterServiceProcessor",
            ANNOTATION_PKG + "EndpointMapperServiceProcessor",
            ANNOTATION_PKG + "ManagedServiceProcessor",
            ANNOTATION_PKG + "UUIDProviderServiceProcessor",
            ANNOTATION_PKG + "WebSocketFactoryServiceProcessor",
            ANNOTATION_PKG + "WebSocketHandlerServiceProcessor",
            ANNOTATION_PKG + "WebSocketProcessorServiceProcessor",
            ANNOTATION_PKG + "WebSocketProtocolServiceProcessor"
    );

    /**
     * Pool implementation types that depend on optional commons-pool2.
     * These should only be registered when {@code org.apache.commons.pool2.PooledObjectFactory}
     * is available on the classpath.
     */
    private static final List<String> POOL_TYPES = List.of(
            "org.atmosphere.pool.UnboundedApachePoolableProvider",
            "org.atmosphere.pool.BoundedApachePoolableProvider"
    );

    /**
     * Returns the fully-qualified class names of all core Atmosphere types that
     * require reflective access at runtime.
     *
     * @return unmodifiable list of core type names
     */
    public static List<String> coreTypes() {
        return CORE_TYPES;
    }

    /**
     * Returns the fully-qualified class names of all Atmosphere annotation processor
     * classes that are instantiated reflectively by {@code AnnotationHandler}.
     *
     * @return unmodifiable list of annotation processor type names
     */
    public static List<String> annotationProcessors() {
        return ANNOTATION_PROCESSORS;
    }

    /**
     * Returns the fully-qualified class names of pool implementation types that
     * depend on optional commons-pool2.
     *
     * @return unmodifiable list of pool type names
     */
    public static List<String> poolTypes() {
        return POOL_TYPES;
    }
}
