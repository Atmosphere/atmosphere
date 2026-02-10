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
package org.atmosphere.quarkus.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.undertow.deployment.IgnoredServletContainerInitializerBuildItem;
import io.quarkus.undertow.deployment.ServletBuildItem;
import io.quarkus.websockets.client.deployment.ServerWebSocketContainerBuildItem;
import org.atmosphere.quarkus.runtime.AtmosphereConfig;
import org.atmosphere.quarkus.runtime.AtmosphereRecorder;
import org.atmosphere.quarkus.runtime.QuarkusAtmosphereServlet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AtmosphereProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereProcessor.class);

    private static final String FEATURE = "atmosphere";

    private static final DotName[] ATMOSPHERE_ANNOTATIONS = {
            DotName.createSimple("org.atmosphere.config.service.AtmosphereHandlerService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterCacheService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterFilterService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterFactoryService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterService"),
            DotName.createSimple("org.atmosphere.config.service.MeteorService"),
            DotName.createSimple("org.atmosphere.config.service.WebSocketFactoryService"),
            DotName.createSimple("org.atmosphere.config.service.WebSocketHandlerService"),
            DotName.createSimple("org.atmosphere.config.service.WebSocketProtocolService"),
            DotName.createSimple("org.atmosphere.config.service.AtmosphereInterceptorService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterListenerService"),
            DotName.createSimple("org.atmosphere.config.service.AsyncSupportService"),
            DotName.createSimple("org.atmosphere.config.service.AsyncSupportListenerService"),
            DotName.createSimple("org.atmosphere.config.service.WebSocketProcessorService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterCacheInspectorService"),
            DotName.createSimple("org.atmosphere.config.service.ManagedService"),
            DotName.createSimple("org.atmosphere.config.service.AtmosphereService"),
            DotName.createSimple("org.atmosphere.config.service.EndpointMapperService"),
            DotName.createSimple("org.atmosphere.config.service.BroadcasterCacheListenerService"),
            DotName.createSimple("org.atmosphere.config.AtmosphereAnnotation"),
            DotName.createSimple("org.atmosphere.config.service.AtmosphereResourceFactoryService"),
            DotName.createSimple("org.atmosphere.config.service.AtmosphereFrameworkListenerService"),
            DotName.createSimple("org.atmosphere.config.service.AtmosphereResourceListenerService"),
            DotName.createSimple("org.atmosphere.config.service.UUIDProviderService"),
    };

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    IndexDependencyBuildItem indexAtmosphereRuntime() {
        return new IndexDependencyBuildItem("org.atmosphere", "atmosphere-runtime");
    }

    @BuildStep
    void ignoreAtmosphereScis(BuildProducer<IgnoredServletContainerInitializerBuildItem> ignored) {
        // Suppress both Atmosphere SCIs. We manage annotation scanning at build time
        // via Jandex, and framework initialization via QuarkusAtmosphereServlet.
        // ContainerInitializer would create a redundant AtmosphereFramework and
        // attempt to register JSR356AsyncSupport which fails in Quarkus (UT003017).
        ignored.produce(new IgnoredServletContainerInitializerBuildItem(
                "org.atmosphere.cpr.AnnotationScanningServletContainerInitializer"));
        ignored.produce(new IgnoredServletContainerInitializerBuildItem(
                "org.atmosphere.cpr.ContainerInitializer"));
    }

    @BuildStep
    AtmosphereAnnotationsBuildItem scanAnnotations(CombinedIndexBuildItem combinedIndex) {
        IndexView index = combinedIndex.getIndex();
        Map<String, List<String>> annotationClassNames = new HashMap<>();

        for (DotName annotationName : ATMOSPHERE_ANNOTATIONS) {
            Collection<AnnotationInstance> instances = index.getAnnotations(annotationName);
            if (!instances.isEmpty()) {
                List<String> classNames = new ArrayList<>();
                for (AnnotationInstance instance : instances) {
                    if (instance.target() != null && instance.target().asClass() != null) {
                        classNames.add(instance.target().asClass().name().toString());
                    }
                }
                if (!classNames.isEmpty()) {
                    annotationClassNames.put(annotationName.toString(), classNames);
                    logger.debug("Found {} classes annotated with @{}",
                            classNames.size(), annotationName.local());
                }
            }
        }

        logger.info("Atmosphere build-time scan found {} annotated classes across {} annotation types",
                annotationClassNames.values().stream().mapToInt(List::size).sum(),
                annotationClassNames.size());

        return new AtmosphereAnnotationsBuildItem(annotationClassNames);
    }

    @BuildStep
    void registerReflection(AtmosphereAnnotationsBuildItem annotations,
                            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        List<String> allClassNames = new ArrayList<>();
        for (List<String> classNames : annotations.getAnnotationClassNames().values()) {
            allClassNames.addAll(classNames);
        }

        if (!allClassNames.isEmpty()) {
            reflectiveClasses.produce(
                    ReflectiveClassBuildItem.builder(allClassNames.toArray(new String[0]))
                            .constructors()
                            .methods()
                            .fields()
                            .reason("Atmosphere annotated classes require reflection")
                            .build());
        }

        reflectiveClasses.produce(
                ReflectiveClassBuildItem.builder(
                                "org.atmosphere.quarkus.runtime.QuarkusAtmosphereObjectFactory",
                                "org.atmosphere.quarkus.runtime.QuarkusAtmosphereServlet",
                                "org.atmosphere.quarkus.runtime.QuarkusJSR356AsyncSupport",
                                "org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator",
                                "org.atmosphere.container.JSR356Endpoint",
                                "org.atmosphere.cpr.AtmosphereFramework",
                                "org.atmosphere.cpr.DefaultBroadcaster",
                                "org.atmosphere.cpr.DefaultBroadcasterFactory",
                                "org.atmosphere.cpr.DefaultAtmosphereResourceFactory",
                                "org.atmosphere.cpr.DefaultMetaBroadcaster",
                                "org.atmosphere.cpr.DefaultAtmosphereResourceSessionFactory",
                                "org.atmosphere.inject.InjectableObjectFactory",
                                "org.atmosphere.inject.AtmosphereProducers")
                        .constructors()
                        .methods()
                        .fields()
                        .reason("Atmosphere core classes require reflection")
                        .build());
    }

    /**
     * Registers JSR-356 WebSocket endpoints with the Quarkus-managed ServerWebSocketContainer.
     * This runs at STATIC_INIT after the container is created by quarkus-websockets-client,
     * but before deploymentComplete is set, so addEndpoint() succeeds.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void registerWebSocketEndpoints(AtmosphereRecorder recorder,
                                    ServerWebSocketContainerBuildItem container) {
        recorder.registerWebSocketEndpoints(container.getContainer());
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    ServletBuildItem registerServlet(AtmosphereRecorder recorder,
                                     AtmosphereConfig config,
                                     AtmosphereAnnotationsBuildItem annotations) {
        ServletBuildItem.Builder builder = ServletBuildItem.builder(
                        "AtmosphereServlet", QuarkusAtmosphereServlet.class.getName())
                .addMapping(config.servletPath())
                .setLoadOnStartup(config.loadOnStartup())
                .setAsyncSupported(true)
                .setInstanceFactory(
                        recorder.createInstanceFactory(annotations.getAnnotationClassNames()));

        builder.addInitParam("org.atmosphere.cpr.AtmosphereFramework.DISABLE_ATMOSPHERE_INITIALIZER", "true");

        // Tell Atmosphere to use our Quarkus-aware object factory. Setting this via init param
        // ensures the factory is created during framework.init() -> configureObjectFactory(),
        // which is the correct lifecycle point. Setting it programmatically before init() does
        // not survive because lookupDefaultObjectFactoryType() can overwrite it.
        builder.addInitParam("org.atmosphere.cpr.objectFactory",
                "org.atmosphere.quarkus.runtime.QuarkusAtmosphereObjectFactory");

        // Use our Quarkus-specific async support instead of JSR356AsyncSupport.
        // JSR356AsyncSupport's constructor calls container.addEndpoint() which fails in
        // Quarkus with "Cannot add endpoint after deployment" (UT003017). Our replacement
        // extends Servlet30CometSupport + supportWebSocket()=true, while the actual
        // endpoints are registered via registerWebSocketEndpoints() at STATIC_INIT.
        builder.addInitParam("org.atmosphere.cpr.asyncSupport",
                "org.atmosphere.quarkus.runtime.QuarkusJSR356AsyncSupport");

        // Use "all" to trigger scan of the pre-populated Jandex annotation map.
        // Atmosphere's scan(String) checks getClass().getClassLoader().getResource() which
        // fails with Quarkus's classloader for real package names. "all" bypasses that check.
        builder.addInitParam("org.atmosphere.cpr.packages", "all");

        if (config.sessionSupport()) {
            builder.addInitParam("org.atmosphere.cpr.sessionSupport", "true");
        }

        config.broadcasterClass().ifPresent(b ->
                builder.addInitParam("org.atmosphere.cpr.broadcasterClass", b));

        config.broadcasterCacheClass().ifPresent(b ->
                builder.addInitParam("org.atmosphere.cpr.broadcasterCacheClass", b));

        config.heartbeatIntervalInSeconds().ifPresent(h ->
                builder.addInitParam("org.atmosphere.cpr.AtmosphereResource.heartbeatFrequencyInSeconds",
                        String.valueOf(h)));

        for (Map.Entry<String, String> entry : config.initParams().entrySet()) {
            builder.addInitParam(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }
}
