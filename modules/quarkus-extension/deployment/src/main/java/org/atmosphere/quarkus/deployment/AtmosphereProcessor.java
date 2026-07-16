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

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;
import io.quarkus.undertow.deployment.IgnoredServletContainerInitializerBuildItem;
import io.quarkus.undertow.deployment.ServletBuildItem;
import io.quarkus.websockets.client.deployment.ServerWebSocketContainerBuildItem;
import org.atmosphere.cpr.AtmosphereAnnotations;
import org.atmosphere.cpr.AtmosphereReflectiveTypes;
import org.atmosphere.quarkus.runtime.AtmosphereConfig;
import org.atmosphere.quarkus.runtime.AtmosphereConsoleInfoServlet;
import org.atmosphere.quarkus.runtime.AtmosphereRecorder;
import org.atmosphere.quarkus.runtime.QuarkusAtmosphereServlet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quarkus build-time processor for the Atmosphere extension. Scans for Atmosphere annotations,
 * registers discovered classes for reflection, configures the Atmosphere servlet, and sets up
 * JSR 356 WebSocket endpoints.
 */
class AtmosphereProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereProcessor.class);

    private static final String FEATURE = "atmosphere";

    // Source of truth: AtmosphereAnnotations.coreAnnotationNames(), augmented with
    // optional annotations resolved by string so the extension keeps no hard
    // dependency on the modules that declare them. If a module is absent the
    // index returns no instances and the slot is simply dropped.
    //   - @AiEndpoint  (atmosphere-ai)
    //   - @Agent       (atmosphere-agent) — drives MCP / A2A / AG-UI registration
    //                  via AgentProcessor; without it @Agent-based endpoints
    //                  (including MCP) never register on Quarkus.
    //   - @Coordinator (atmosphere-coordinator) — drives fleet wiring via
    //                  CoordinatorProcessor; without it @Coordinator classes
    //                  silently never register on Quarkus while the same class
    //                  works on Spring Boot / plain servlet (Invariant #7).
    private static final List<String> OPTIONAL_ANNOTATIONS = List.of(
            "org.atmosphere.ai.annotation.AiEndpoint",
            "org.atmosphere.agent.annotation.Agent",
            "org.atmosphere.coordinator.annotation.Coordinator");

    private static final DotName[] ATMOSPHERE_ANNOTATIONS =
            java.util.stream.Stream.concat(
                            AtmosphereAnnotations.coreAnnotationNames().stream(),
                            OPTIONAL_ANNOTATIONS.stream())
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

    /**
     * Index the optional {@code atmosphere-agent} and {@code atmosphere-mcp} jars
     * so their {@code @AtmosphereAnnotation} processors — notably
     * {@code AgentProcessor}, which turns an {@code @Agent} class into MCP / A2A /
     * AG-UI endpoints — are in the Jandex index and registered as annotation
     * handlers. Gated on the application actually declaring an {@code @Agent}
     * class: indexing them unconditionally would pull {@code AgentProcessor} into
     * native link-at-build-time even for apps that don't use {@code @Agent}, and
     * {@code AgentProcessor} references optional sibling modules (e.g. AG-UI
     * {@code RunContext}) that may be absent — which fails native image analysis.
     */
    @BuildStep
    void indexOptionalAgentModules(ApplicationIndexBuildItem applicationIndex,
                                   BuildProducer<IndexDependencyBuildItem> indexDependency) {
        var agent = DotName.createSimple("org.atmosphere.agent.annotation.Agent");
        if (applicationIndex.getIndex().getAnnotations(agent).isEmpty()) {
            return;
        }
        indexDependency.produce(new IndexDependencyBuildItem("org.atmosphere", "atmosphere-agent"));
        indexDependency.produce(new IndexDependencyBuildItem("org.atmosphere", "atmosphere-mcp"));
    }

    /**
     * Index the optional {@code atmosphere-coordinator} jar so its
     * {@code @AtmosphereAnnotation} processor — {@code CoordinatorProcessor},
     * which turns an {@code @Coordinator} class into an agent endpoint with
     * fleet wiring — is in the Jandex index and registered as an annotation
     * handler. Gated on the application actually declaring an
     * {@code @Coordinator} class, for the same reason {@code atmosphere-agent}
     * indexing is gated above: {@code CoordinatorProcessor} references optional
     * sibling modules (a2a transport, mcp, agui, channels) that may be absent,
     * which fails native image analysis when pulled into link-at-build-time
     * unconditionally.
     */
    @BuildStep
    void indexOptionalCoordinatorModule(ApplicationIndexBuildItem applicationIndex,
                                        BuildProducer<IndexDependencyBuildItem> indexDependency) {
        var coordinator = DotName.createSimple("org.atmosphere.coordinator.annotation.Coordinator");
        if (applicationIndex.getIndex().getAnnotations(coordinator).isEmpty()) {
            return;
        }
        indexDependency.produce(new IndexDependencyBuildItem("org.atmosphere", "atmosphere-coordinator"));
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
        // JettyHttp3AsyncSupport has compile-time Jetty imports (the Atmosphere
        // pattern for auto-discovered transports). On standard JVM the resolver
        // only loads the class when Jetty is present. GraalVM native image
        // eagerly verifies all classes — defer to runtime so Quarkus/Undertow
        // builds don't fail on missing Jetty classes.
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "org.atmosphere.container.JettyHttp3AsyncSupport"));
        // MetricsController has compile-time Micrometer imports. The admin
        // module is optional — on standard JVM the class is only loaded when
        // Micrometer is present. GraalVM/JDK 25+ eagerly links it.
        runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                "org.atmosphere.admin.metrics.MetricsController"));
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
        } catch (ClassNotFoundException ex) {
            logger.trace("commons-pool2 not on classpath; skip pool class registration", ex);
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

        // Explicit broadcaster-cache-class config wins. Otherwise, when
        // quarkus.atmosphere.cache-enabled=true (Spring Boot parity for
        // AtmosphereCacheAutoConfiguration), default to BoundedMemoryCache
        // and install MessageAckInterceptor so missed-message recovery
        // works out of the box.
        if (config.broadcasterCacheClass().isPresent()) {
            builder.addInitParam("org.atmosphere.cpr.broadcasterCacheClass",
                    config.broadcasterCacheClass().get());
        } else if (config.cacheEnabled()) {
            builder.addInitParam("org.atmosphere.cpr.broadcasterCacheClass",
                    "org.atmosphere.cache.BoundedMemoryCache");
            // AtmosphereInterceptor init params accept a comma-separated list of
            // FQNs that AtmosphereFramework.configureAtmosphereInterceptor() expands
            // into the running interceptor chain — same hook Spring Boot's
            // @Bean MessageAckInterceptor uses, just plumbed through the servlet
            // init param instead of an autoconfigured bean.
            builder.addInitParam("org.atmosphere.cpr.AtmosphereInterceptor",
                    "org.atmosphere.interceptor.MessageAckInterceptor");
            logger.info("Atmosphere cache enabled — installing BoundedMemoryCache + MessageAckInterceptor");
        }

        config.heartbeatInterval().ifPresent(h ->
                builder.addInitParam("org.atmosphere.cpr.AtmosphereResource.heartbeatFrequencyInSeconds",
                        String.valueOf(h.toSeconds())));

        // RAG injection-safety screen (OWASP Agentic A04): AiEndpointProcessor
        // wraps every @AiEndpoint ContextProvider so retrieved documents are
        // checked before reaching the LLM. On by default and fail-closed;
        // disable with quarkus.atmosphere.ai.rag.safety.enabled=false. Keys are
        // literals mirroring RagSafetyConfig in atmosphere-ai so this build-time
        // deployment module needs no compile dep on the AI runtime module.
        var ragSafety = config.ai().rag().safety();
        builder.addInitParam("org.atmosphere.ai.rag.safety.enabled", String.valueOf(ragSafety.enabled()));
        builder.addInitParam("org.atmosphere.ai.rag.safety.tier", ragSafety.tier());
        builder.addInitParam("org.atmosphere.ai.rag.safety.on-breach", ragSafety.onBreach());
        builder.addInitParam("org.atmosphere.ai.rag.safety.fail-open", String.valueOf(ragSafety.failOpen()));

        // Long-term-memory injection-safety screen (OWASP Agentic A03):
        // AiEndpointProcessor screens every fact extracted into a LongTermMemory
        // store before it is persisted. On by default and fail-closed; disable
        // with quarkus.atmosphere.ai.memory.safety.enabled=false. Keys are
        // literals mirroring MemorySafetyConfig in atmosphere-ai.
        var memorySafety = config.ai().memory().safety();
        builder.addInitParam("org.atmosphere.ai.memory.safety.enabled", String.valueOf(memorySafety.enabled()));
        builder.addInitParam("org.atmosphere.ai.memory.safety.tier", memorySafety.tier());
        builder.addInitParam("org.atmosphere.ai.memory.safety.on-breach", memorySafety.onBreach());
        builder.addInitParam("org.atmosphere.ai.memory.safety.fail-open", String.valueOf(memorySafety.failOpen()));

        // Durable governance feedback (opt-in, off by default): when enabled,
        // AiEndpointProcessor persists deny/prefer decisions to the resolved
        // LongTermMemory (provenance-tagged) so the feedback interceptor recalls them
        // across sessions and restarts. Enable with
        // quarkus.atmosphere.ai.governance.memory.enabled=true. Keys are literals
        // mirroring GovernanceMemoryConfig in atmosphere-ai so this build-time module
        // needs no compile dep on the AI runtime module.
        var governanceMemory = config.ai().governance().memory();
        builder.addInitParam("org.atmosphere.ai.governance.memory.enabled",
                String.valueOf(governanceMemory.enabled()));
        builder.addInitParam("org.atmosphere.ai.governance.memory.ttl-seconds",
                String.valueOf(governanceMemory.ttlSeconds()));
        builder.addInitParam("org.atmosphere.ai.governance.memory.confidence",
                String.valueOf(governanceMemory.confidence()));
        builder.addInitParam("org.atmosphere.ai.governance.memory.min-confidence",
                String.valueOf(governanceMemory.minConfidence()));

        // Agent-harness preset: the app-wide switch governing Atmosphere's
        // deep-agent primitives, read once per framework by AiEndpointProcessor.
        // The switch is tri-state, so the init-param bridges only when the
        // operator set it: absent leaves the annotation-governed default in
        // charge, while an explicit false must reach the runtime as "false" —
        // the kill switch that beats every annotation. Keys are literals
        // mirroring the org.atmosphere.ai.harness.* namespace in atmosphere-ai
        // so this build-time deployment module needs no compile dep on the AI
        // runtime module. The compaction and prompt-cache seams work
        // independent of the harness, so they bridge whenever set.
        var harness = config.ai().harness();
        harness.enabled().ifPresent(enabled ->
                builder.addInitParam("org.atmosphere.ai.harness.enabled", String.valueOf(enabled)));
        harness.excludePaths().ifPresent(paths ->
                builder.addInitParam("org.atmosphere.ai.harness.exclude-paths", String.join(",", paths)));
        harness.compaction().ifPresent(strategy ->
                builder.addInitParam("org.atmosphere.ai.compaction", strategy));
        harness.promptCacheDefault().ifPresent(policy ->
                builder.addInitParam("org.atmosphere.ai.prompt-cache.default", policy));

        for (Map.Entry<String, String> entry : config.initParams().entrySet()) {
            builder.addInitParam(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    /**
     * Registers the bundled-Console info servlet at {@code /api/console/info}
     * so the Vue Console UI gets the same {@code subtitle / endpoint /
     * runtime / mode} payload it gets from the Spring Boot starter's
     * {@code AtmosphereConsoleInfoEndpoint}. Closes the parity gap that
     * previously made {@code GET /api/console/info} return 404 on Quarkus
     * — the Vue app then fell back to AI-mode defaults regardless of
     * whether the registered handler was AI-shaped or {@code @ManagedService}.
     */
    @BuildStep
    ServletBuildItem registerConsoleInfoServlet(AtmosphereConfig config) {
        ServletBuildItem.Builder builder = ServletBuildItem.builder(
                        "AtmosphereConsoleInfoServlet",
                        AtmosphereConsoleInfoServlet.class.getName())
                .addMapping("/api/console/info")
                .setLoadOnStartup(2)
                .setAsyncSupported(false);
        config.consoleSubtitle().ifPresent(s ->
                builder.addInitParam(
                        AtmosphereConsoleInfoServlet.CONSOLE_SUBTITLE_PARAM, s));
        config.consoleEndpoint().ifPresent(s ->
                builder.addInitParam(
                        AtmosphereConsoleInfoServlet.CONSOLE_ENDPOINT_PARAM, s));
        config.consoleTransport().ifPresent(s ->
                builder.addInitParam(
                        AtmosphereConsoleInfoServlet.CONSOLE_TRANSPORT_PARAM, s));
        return builder.build();
    }

    /**
     * Registers {@code BoundedMemoryCache} and {@code MessageAckInterceptor}
     * for GraalVM reflection when {@code quarkus.atmosphere.cache-enabled=true}.
     * The servlet wiring (see {@link #registerServlet}) sets the {@code broadcasterCacheClass}
     * and {@code AtmosphereInterceptor} init params so Atmosphere instantiates them
     * via reflection at servlet init; the build step above keeps that reflection path
     * working in native image builds. Quarkus parity for the Spring Boot starter's
     * {@code AtmosphereCacheAutoConfiguration}.
     */
    @BuildStep
    void registerCacheReflection(AtmosphereConfig config,
                                 BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        if (!config.cacheEnabled() && config.broadcasterCacheClass().isEmpty()) {
            return;
        }
        reflectiveClasses.produce(
                ReflectiveClassBuildItem.builder(
                                "org.atmosphere.cache.BoundedMemoryCache",
                                "org.atmosphere.interceptor.MessageAckInterceptor")
                        .constructors()
                        .methods()
                        .reason("Atmosphere cache enabled (quarkus.atmosphere.cache-enabled=true)")
                        .build());
        logger.info("Atmosphere cache reflection registered for native image");
    }

    /**
     * Quarkus parity for {@code AtmosphereActuatorAutoConfiguration}. When
     * {@code quarkus-smallrye-health} is on the classpath the health check bean
     * is registered with the Quarkus Arc container and surfaced under
     * {@code /q/health}, {@code /q/health/live}, and {@code /q/health/ready}
     * by the SmallRye Health processor. Gated on {@link Capability#SMALLRYE_HEALTH}
     * so users without smallrye-health pay no startup cost.
     */
    @BuildStep
    void registerHealthCheck(Capabilities capabilities,
                             BuildProducer<AdditionalBeanBuildItem> beans,
                             BuildProducer<HealthBuildItem> health) {
        if (!capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            logger.debug("quarkus-smallrye-health absent — skipping AtmosphereHealthCheck registration");
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(
                "org.atmosphere.quarkus.runtime.AtmosphereHealthCheck"));
        health.produce(new HealthBuildItem(
                "org.atmosphere.quarkus.runtime.AtmosphereHealthCheck",
                true));
        logger.info("Atmosphere SmallRye Health check registered at /q/health/atmosphere");
    }

    /**
     * Quarkus parity for {@code AtmosphereMetricsAutoConfiguration}. When the
     * Quarkus Micrometer extension is on the classpath, registers the
     * {@code AtmosphereMetricsProducer} bean. The producer injects the
     * Micrometer {@link io.micrometer.core.instrument.MeterRegistry} bean
     * Quarkus has already wired and binds Atmosphere's per-resource gauges,
     * broadcast counters, and timers on {@code @Observes StartupEvent}.
     * Gating on a class lookup keeps the build step inert in classpaths that
     * do not pull {@code quarkus-micrometer}.
     */
    @BuildStep
    void registerMetricsProducer(BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!isClassPresent("io.micrometer.core.instrument.MeterRegistry")) {
            logger.debug("micrometer-core absent — skipping AtmosphereMetricsProducer registration");
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(
                "org.atmosphere.quarkus.runtime.AtmosphereMetricsProducer"));
        logger.info("Atmosphere Micrometer metrics producer registered (atmosphere.* metrics)");
    }

    /**
     * Quarkus parity for {@code AtmosphereTracingAutoConfiguration}. When
     * {@code quarkus-opentelemetry} is on the classpath, registers the
     * {@code AtmosphereTracingProducer} bean. On {@code @Observes StartupEvent}
     * the producer instantiates {@code AtmosphereTracing} with the running
     * {@link io.opentelemetry.api.OpenTelemetry} bean and binds it as an
     * interceptor on the framework. Gated on
     * {@link Capability#OPENTELEMETRY_TRACER} so users without the OTel
     * extension are unaffected.
     */
    @BuildStep
    void registerTracingProducer(Capabilities capabilities,
                                 BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!capabilities.isPresent(Capability.OPENTELEMETRY_TRACER)) {
            logger.debug("quarkus-opentelemetry absent — skipping AtmosphereTracingProducer registration");
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(
                "org.atmosphere.quarkus.runtime.AtmosphereTracingProducer"));
        logger.info("Atmosphere OpenTelemetry tracing producer registered");
    }

    /**
     * Quarkus parity for {@code AtmosphereGovernanceMetricsAutoConfiguration}.
     * Stacks on the Micrometer step (the producer injects {@link io.micrometer.core.instrument.MeterRegistry})
     * and additionally requires {@code atmosphere-ai} on the classpath
     * ({@code GovernanceMetricsHolder} lives there). When both are present the
     * producer wraps the running {@link io.micrometer.core.instrument.MeterRegistry}
     * in a {@code QuarkusMicrometerGovernanceMetrics} and installs it via
     * {@code GovernanceMetricsHolder.install(...)}, so per-policy similarity
     * histograms and evaluation timers show up under
     * {@code atmosphere.governance.*} alongside the rest of Atmosphere's
     * meters.
     */
    @BuildStep
    void registerGovernanceMetricsProducer(BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!isClassPresent("io.micrometer.core.instrument.MeterRegistry")) {
            return;
        }
        if (!isClassPresent("org.atmosphere.ai.governance.GovernanceMetricsHolder")) {
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(
                "org.atmosphere.quarkus.runtime.AtmosphereGovernanceMetricsProducer"));
        logger.info("Atmosphere governance metrics producer registered "
                + "(atmosphere.governance.* meters via GovernanceMetricsHolder)");
    }

    /**
     * Quarkus parity for the Spring Boot starter's {@code DurableRunSpineInstaller}
     * (in {@code AtmosphereAiAutoConfiguration}). Requires {@code atmosphere-ai} on
     * the classpath ({@code DurableRunSpineHolder} lives there). When present the
     * producer bean is registered; on startup it reads
     * {@code quarkus.atmosphere.durable-runs.*} and, when {@code enabled=true}
     * (or the agent-harness preset implies it — see {@code AtmosphereConfig.Ai.Harness}),
     * installs the effect-journal-backed {@code DurableRunSpine} so committed LLM
     * rounds and tool calls replay deterministically after a crash. The bean is a
     * no-op when durable runs are disabled, so registering it unconditionally (when
     * {@code atmosphere-ai} is present) carries no runtime cost beyond one startup
     * observer.
     */
    @BuildStep
    void registerDurableRunsProducer(BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!isClassPresent("org.atmosphere.ai.resume.DurableRunSpineHolder")) {
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(
                "org.atmosphere.quarkus.runtime.AtmosphereDurableRunsProducer"));
        logger.info("Atmosphere durable agent runs producer registered "
                + "(quarkus.atmosphere.durable-runs.enabled gates installation)");
    }

    /**
     * Quarkus parity for the Spring Boot starters' {@code TapeInstaller} (in
     * {@code AtmosphereAiAutoConfiguration}). Requires {@code atmosphere-ai} on
     * the classpath ({@code TapeSupport} lives there). When present the producer
     * bean is registered; on startup it reads {@code quarkus.atmosphere.ai.tape.*}
     * and, when {@code enabled=true}, installs a {@code TapeRecorder} via
     * {@code TapeSupport} so every AI streaming session is recorded as an
     * append-only per-run step log. The bean is a no-op when the tape is
     * disabled, so registering it unconditionally (when {@code atmosphere-ai} is
     * present) carries no runtime cost beyond one startup observer.
     */
    @BuildStep
    void registerTapeProducer(BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!isClassPresent("org.atmosphere.ai.tape.TapeSupport")) {
            return;
        }
        beans.produce(AdditionalBeanBuildItem.unremovableOf(
                "org.atmosphere.quarkus.runtime.AtmosphereTapeProducer"));
        logger.info("Atmosphere session tape producer registered "
                + "(quarkus.atmosphere.ai.tape.enabled gates installation)");
    }

    /**
     * Build-time classpath detection for optional integrations. Quarkus
     * extensions resolve dependencies through their own classloader, which
     * matches the build classpath for the extension processor; this is the
     * canonical pattern used across the Quarkus codebase for soft deps.
     */
    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name, false, AtmosphereProcessor.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
