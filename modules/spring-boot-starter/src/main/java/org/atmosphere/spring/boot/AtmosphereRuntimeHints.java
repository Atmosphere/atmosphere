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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.DefaultAtmosphereResourceFactory;
import org.atmosphere.cpr.DefaultAtmosphereResourceSessionFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.DefaultMetaBroadcaster;
import org.atmosphere.config.managed.ManagedAtmosphereHandler;
import org.atmosphere.inject.AtmosphereConfigInjectable;
import org.atmosphere.inject.AtmosphereFrameworkInjectable;
import org.atmosphere.inject.AtmosphereProducers;
import org.atmosphere.inject.AtmosphereRequestIntrospector;
import org.atmosphere.inject.AtmosphereResourceEventIntrospector;
import org.atmosphere.inject.AtmosphereResourceFactoryInjectable;
import org.atmosphere.inject.AtmosphereResourceIntrospector;
import org.atmosphere.inject.AtmosphereResourceSessionFactoryInjectable;
import org.atmosphere.inject.AtmosphereResponseIntrospector;
import org.atmosphere.inject.BroadcasterFactoryInjectable;
import org.atmosphere.inject.BroadcasterIntrospector;
import org.atmosphere.inject.InjectableObjectFactory;
import org.atmosphere.inject.MetaBroadcasterInjectable;
import org.atmosphere.inject.PathParamIntrospector;
import org.atmosphere.inject.PostConstructIntrospector;
import org.atmosphere.inject.WebSocketFactoryInjectable;
import org.atmosphere.config.managed.AtmosphereHandlerServiceInterceptor;
import org.atmosphere.pool.BoundedApachePoolableProvider;
import org.atmosphere.pool.UnboundedApachePoolableProvider;
import org.atmosphere.websocket.DefaultWebSocketFactory;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * {@link RuntimeHintsRegistrar} for Atmosphere Framework classes that are
 * instantiated reflectively at runtime. Registers reflection hints for
 * core framework classes, injectable SPI implementations, annotation
 * processors, and ServiceLoader resource files.
 */
public class AtmosphereRuntimeHints implements RuntimeHintsRegistrar {

    private static final MemberCategory[] HINT_CATEGORIES = {
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS
    };

    private static final String ANNOTATION_PKG = "org.atmosphere.annotation.";

    private static final String[] ANNOTATION_PROCESSORS = {
            "AsyncSupportListenerServiceProcessor",
            "AsyncSupportServiceProcessor",
            "AtmosphereFrameworkServiceProcessor",
            "AtmosphereHandlerServiceProcessor",
            "AtmosphereInterceptorServiceProcessor",
            "AtmosphereResourceFactoryServiceProcessor",
            "AtmosphereResourceListenerServiceProcessor",
            "AtmosphereServiceProcessor",
            "BroadcastFilterServiceProcessor",
            "BroadcasterCacheInspectorServiceProcessor",
            "BroadcasterCacheListenererviceProcessor",
            "BroadcasterCacheServiceProcessor",
            "BroadcasterFactoryServiceProcessor",
            "BroadcasterListenerServiceProcessor",
            "BroadcasterServiceProcessor",
            "EndpointMapperServiceProcessor",
            "ManagedServiceProcessor",
            "UUIDProviderServiceProcessor",
            "WebSocketFactoryServiceProcessor",
            "WebSocketHandlerServiceProcessor",
            "WebSocketProcessorServiceProcessor",
            "WebSocketProtocolServiceProcessor"
    };

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        ReflectionHints reflection = hints.reflection();

        // Spring-specific
        registerType(reflection, SpringAtmosphereObjectFactory.class);

        // Injectable SPI implementations
        registerType(reflection, AtmosphereConfigInjectable.class);
        registerType(reflection, AtmosphereFrameworkInjectable.class);
        registerType(reflection, AtmosphereResourceFactoryInjectable.class);
        registerType(reflection, AtmosphereResourceSessionFactoryInjectable.class);
        registerType(reflection, BroadcasterFactoryInjectable.class);
        registerType(reflection, MetaBroadcasterInjectable.class);
        registerType(reflection, WebSocketFactoryInjectable.class);
        registerType(reflection, PostConstructIntrospector.class);
        registerType(reflection, BroadcasterIntrospector.class);
        registerType(reflection, AtmosphereResourceIntrospector.class);
        registerType(reflection, AtmosphereRequestIntrospector.class);
        registerType(reflection, AtmosphereResponseIntrospector.class);
        registerType(reflection, AtmosphereResourceEventIntrospector.class);
        registerType(reflection, PathParamIntrospector.class);

        // Core reflective
        registerType(reflection, AtmosphereResourceImpl.class);
        registerType(reflection, AtmosphereRequestImpl.class);
        registerType(reflection, AtmosphereResponseImpl.class);
        registerType(reflection, ManagedAtmosphereHandler.class);
        registerType(reflection, AtmosphereHandlerServiceInterceptor.class);
        registerTypeByName(reflection,
                "org.atmosphere.cpr.AtmosphereFramework$DefaultAtmosphereObjectFactory");
        registerType(reflection, InjectableObjectFactory.class);
        registerType(reflection, AtmosphereProducers.class);

        // Framework infrastructure
        registerType(reflection, AtmosphereFramework.class);
        registerType(reflection, DefaultBroadcaster.class);
        registerType(reflection, DefaultBroadcasterFactory.class);
        registerType(reflection, DefaultAtmosphereResourceFactory.class);
        registerType(reflection, DefaultMetaBroadcaster.class);
        registerType(reflection, DefaultAtmosphereResourceSessionFactory.class);
        registerType(reflection, SimpleHttpProtocol.class);

        // AsyncSupport
        registerTypeByName(reflection, "org.atmosphere.container.JSR356AsyncSupport");
        registerTypeByName(reflection, "org.atmosphere.container.Servlet30CometSupport");

        // Pool implementations (only if commons-pool2 is on the classpath)
        if (classLoader != null) {
            try {
                classLoader.loadClass("org.apache.commons.pool2.PooledObjectFactory");
                registerType(reflection, UnboundedApachePoolableProvider.class);
                registerType(reflection, BoundedApachePoolableProvider.class);
            } catch (ClassNotFoundException ignored) {
                // commons-pool2 not available; skip pool class registration
            }
        }

        // WebSocket
        registerType(reflection, DefaultWebSocketProcessor.class);
        registerType(reflection, DefaultWebSocketFactory.class);
        registerTypeByName(reflection, "org.atmosphere.container.JSR356Endpoint");

        // Annotation processors (instantiated reflectively by AnnotationHandler)
        for (String processor : ANNOTATION_PROCESSORS) {
            registerTypeByName(reflection, ANNOTATION_PKG + processor);
        }

        // ServiceLoader resource files
        hints.resources().registerPattern("META-INF/services/org.atmosphere.inject.Injectable");
        hints.resources().registerPattern("META-INF/services/org.atmosphere.inject.CDIProducer");
    }

    private void registerType(ReflectionHints reflection, Class<?> type) {
        reflection.registerType(type, HINT_CATEGORIES);
    }

    private void registerTypeByName(ReflectionHints reflection, String typeName) {
        reflection.registerType(TypeReference.of(typeName), HINT_CATEGORIES);
    }
}
