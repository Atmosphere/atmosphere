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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.undertow.deployment.IgnoredServletContainerInitializerBuildItem;
import io.quarkus.undertow.deployment.ServletBuildItem;
import io.quarkus.websockets.client.deployment.ServerWebSocketContainerBuildItem;
import org.atmosphere.cpr.AtmosphereAnnotations;
import org.atmosphere.cpr.AtmosphereReflectiveTypes;
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

    // Source of truth: AtmosphereAnnotations.coreAnnotationNames()
    private static final DotName[] ATMOSPHERE_ANNOTATIONS =
            AtmosphereAnnotations.coreAnnotationNames().stream()
                    .map(DotName::createSimple)
                    .toArray(DotName[]::new);

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
    void registerRuntimeInit(BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit) {
        // ExecutorsFactory creates ScheduledExecutorService with named threads.
        // These threads must not start during native image build.
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "org.atmosphere.util.ExecutorsFactory"));
    }

    /**
     * In native image builds the servlet's init() is skipped at STATIC_INIT to avoid
     * creating thread pools that would be captured in the image heap. This step triggers
     * the actual framework initialization at RUNTIME_INIT after the Undertow deployment
     * is ready and the server is about to start accepting connections.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void deferredFrameworkInit(AtmosphereRecorder recorder) {
        recorder.performDeferredInit();
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

        // Quarkus runtime classes (not in the shared registry — Quarkus-specific)
        reflectiveClasses.produce(
                ReflectiveClassBuildItem.builder(
                                "org.atmosphere.quarkus.runtime.QuarkusAtmosphereObjectFactory",
                                "org.atmosphere.quarkus.runtime.QuarkusAtmosphereServlet",
                                "org.atmosphere.quarkus.runtime.QuarkusJSR356AsyncSupport",
                                "org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator")
                        .constructors()
                        .methods()
                        .fields()
                        .reason("Quarkus Atmosphere runtime classes require reflection")
                        .build());

        // Core Atmosphere types (source of truth: AtmosphereReflectiveTypes)
        reflectiveClasses.produce(
                ReflectiveClassBuildItem.builder(
                                AtmosphereReflectiveTypes.coreTypes().toArray(new String[0]))
                        .constructors()
                        .methods()
                        .fields()
                        .reason("Atmosphere core classes require reflection")
                        .build());
    }

    @BuildStep
    void registerPoolReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        // Pool implementations depend on optional commons-pool2; only register if available
        try {
            Class.forName("org.apache.commons.pool2.PooledObjectFactory", false,
                    Thread.currentThread().getContextClassLoader());
            reflectiveClasses.produce(
                    ReflectiveClassBuildItem.builder(
                                    AtmosphereReflectiveTypes.poolTypes().toArray(new String[0]))
                            .constructors()
                            .methods()
                            .fields()
                            .reason("Atmosphere pool implementations (commons-pool2 present)")
                            .build());
        } catch (ClassNotFoundException ignored) {
            // commons-pool2 not on classpath; skip pool class registration
        }
    }

    @BuildStep
    void registerServiceResources(BuildProducer<NativeImageResourceBuildItem> resources) {
        resources.produce(new NativeImageResourceBuildItem(
                "META-INF/services/org.atmosphere.inject.Injectable",
                "META-INF/services/org.atmosphere.inject.CDIProducer"));
    }

    @BuildStep
    void registerEncoderDecoderClasses(CombinedIndexBuildItem combinedIndex,
                                       BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        IndexView index = combinedIndex.getIndex();
        Set<String> classes = new HashSet<>();

        // @Message has encoders() and decoders()
        var messageName = DotName.createSimple("org.atmosphere.config.service.Message");
        for (AnnotationInstance ann : index.getAnnotations(messageName)) {
            extractClassArrayValues(ann, "encoders", classes);
            extractClassArrayValues(ann, "decoders", classes);
        }

        // @Ready has encoders() only
        var readyName = DotName.createSimple("org.atmosphere.config.service.Ready");
        for (AnnotationInstance ann : index.getAnnotations(readyName)) {
            extractClassArrayValues(ann, "encoders", classes);
        }

        if (!classes.isEmpty()) {
            reflectiveClasses.produce(
                    ReflectiveClassBuildItem.builder(classes.toArray(new String[0]))
                            .constructors()
                            .methods()
                            .reason("Atmosphere Encoder/Decoder classes from @Message/@Ready")
                            .build());
        }
    }

    private void extractClassArrayValues(AnnotationInstance ann, String member, Set<String> out) {
        var value = ann.value(member);
        if (value != null) {
            for (var type : value.asClassArray()) {
                out.add(type.name().toString());
            }
        }
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

    /**
     * Registers a shutdown hook that resets the {@link LazyAtmosphereConfigurator}
     * before Quarkus dev mode live reload re-initializes the servlet. This ensures
     * a fresh CountDownLatch and framework reference for each reload cycle.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void registerConfiguratorShutdownHook(AtmosphereRecorder recorder,
                                          ShutdownContextBuildItem shutdownContext) {
        recorder.registerShutdownHook(shutdownContext);
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

        config.heartbeatInterval().ifPresent(h ->
                builder.addInitParam("org.atmosphere.cpr.AtmosphereResource.heartbeatFrequencyInSeconds",
                        String.valueOf(h.toSeconds())));

        for (Map.Entry<String, String> entry : config.initParams().entrySet()) {
            builder.addInitParam(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }
}
