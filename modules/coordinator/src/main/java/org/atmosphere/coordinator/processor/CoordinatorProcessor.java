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
package org.atmosphere.coordinator.processor;

import org.atmosphere.agent.ClasspathDetector;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.agent.processor.AgentHandler;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.ConversationPersistence;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.PersistentConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.annotation.AnnotationUtil;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.evaluation.ResultEvaluator;
import org.atmosphere.coordinator.fleet.AgentActivityListener;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentLimits;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.fleet.DefaultCircuitBreaker;
import org.atmosphere.coordinator.fleet.ResilientAgentProxy;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.JournalFormat;
import org.atmosphere.coordinator.journal.JournalingAgentFleet;
import org.atmosphere.coordinator.transport.A2aAgentTransport;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.atmosphere.coordinator.transport.LocalAgentTransport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereFrameworkListenerAdapter;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Processes {@link Coordinator} and {@link Fleet} annotations. Composes with
 * {@code AgentProcessor} by calling the same public APIs from the AI module,
 * then adds fleet wiring on top.
 *
 * <p>Processing steps:</p>
 * <ol>
 *   <li>Create instance and inject fields</li>
 *   <li>Parse skill file for system prompt</li>
 *   <li>Scan {@code @Command} methods</li>
 *   <li>Find {@code @Prompt} method</li>
 *   <li>Resolve AI infrastructure (support, memory, tools, metrics)</li>
 *   <li>Parse {@code @Fleet} and resolve each {@code @AgentRef}</li>
 *   <li>Validate fleet (duplicates, cycles)</li>
 *   <li>Create {@link AgentFleet} and inject into handler</li>
 *   <li>Register handler and protocol bridges</li>
 *   <li>Log fleet topology</li>
 * </ol>
 */
@AtmosphereAnnotation(value = Coordinator.class, priority = 100)
public class CoordinatorProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(CoordinatorProcessor.class);
    private static final int DEFAULT_MAX_HISTORY = 20;

    /**
     * Property key under which an externally-managed
     * {@link CoordinationJournal} can be stashed on
     * {@code framework.getAtmosphereConfig().properties()}. When present, the
     * processor uses that journal verbatim and does <strong>not</strong> call
     * {@link CoordinationJournal#start() start()} or
     * {@link CoordinationJournal#stop() stop()} — the bridging container
     * (typically Spring Boot's auto-configuration) owns the lifecycle.
     *
     * <p>This is the bridge between Spring-managed {@code @Bean}-style journals
     * and the processor's discovery path. Without this hook, the processor's
     * {@link java.util.ServiceLoader}-only resolution silently misses Spring
     * beans, the journal stays {@link CoordinationJournal#NOOP NOOP}, and
     * {@code AgentCompleted}/{@code AgentFailed} events are swallowed inside
     * {@link DefaultAgentFleet}.</p>
     */
    public static final String COORDINATION_JOURNAL_PROPERTY =
            "org.atmosphere.coordinator.journal";

    private final Map<String, Set<String>> dependencyGraph = new ConcurrentHashMap<>();

    /**
     * Journals started by this processor on the {@link java.util.ServiceLoader}
     * path. These are lifecycle-owned by the processor and must be
     * {@link CoordinationJournal#stop() stopped} on framework shutdown so
     * resources (file handles, JDBC connections, background flush threads)
     * don't leak.
     *
     * <p>Externally-managed journals (Spring-bean bridge via
     * {@link #COORDINATION_JOURNAL_PROPERTY}) are intentionally absent from
     * this list — their {@code stop()} is owned by the bridging container
     * (typically Spring's {@code @Bean(destroyMethod="stop")}).</p>
     *
     * <p>Entries are appended from {@link #handle(AtmosphereFramework, Class)}
     * inside a successful ServiceLoader resolution. The list is
     * {@link java.util.concurrent.CopyOnWriteArrayList} because shutdown reads
     * it on the framework-destroy thread while {@code handle()} may still be
     * appending on the annotation-scan thread.</p>
     */
    private final List<OwnedJournal> ownedJournals = new CopyOnWriteArrayList<>();

    /**
     * Ensures the shutdown {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     * is registered at most once per {@code (processor, framework)} pair.
     * Multiple {@code @Coordinator} classes share one processor instance (the
     * {@code AnnotationHandler} caches processors by class), so without this
     * guard we would register a duplicate listener for every coordinator
     * found in the same scan.
     */
    private final java.util.Set<AtmosphereFramework> shutdownHookInstalledOn =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(Coordinator.class);
            if (annotation == null) {
                return;
            }

            var coordinatorName = annotation.name();
            var path = "/atmosphere/agent/" + coordinatorName;

            // Step 1: Create instance and inject fields
            var instance = framework.newClassInstance(Object.class, annotatedClass);
            AnnotatedLifecycle.injectFields(framework, instance);

            // Step 2: Parse skill file
            var skillFile = loadSkillFile(annotation.skillFile(), coordinatorName);
            var systemPrompt = skillFile != null ? skillFile.systemPrompt() : "";

            // Step 3: Scan @Command methods
            var commandRegistry = new CommandRegistry();
            commandRegistry.scan(annotatedClass);

            // Step 4: Find @Prompt method
            var promptMethod = findPromptMethod(annotatedClass);
            var promptTarget = promptMethod.getDeclaringClass() == SyntheticPrompt.class
                    ? new SyntheticPrompt() : instance;

            // Step 5: Resolve AI infrastructure
            var settings = resolveSettings();
            var runtime = resolveRuntime(settings);
            var memory = resolveMemory(DEFAULT_MAX_HISTORY);
            var toolRegistry = registerTools(instance);
            var metrics = resolveMetrics();
            var lifecycle = AnnotatedLifecycle.scan(annotatedClass);

            // Step 6: Parse @Fleet and resolve agents
            var fleetAnnotation = annotatedClass.getAnnotation(Fleet.class);
            if (fleetAnnotation == null) {
                throw new IllegalStateException(
                        "@Coordinator '" + coordinatorName + "' must also have @Fleet");
            }
            var activityListeners = resolveActivityListeners();
            var proxies = resolveFleet(framework, fleetAnnotation, coordinatorName,
                    activityListeners);

            // Step 7: Validate fleet
            detectCircularDependencies(coordinatorName, proxies.keySet());

            // Step 8: Create AgentFleet and AiEndpointHandler with injectable
            var evaluators = resolveEvaluators();
            var journalResolution = resolveJournal(framework);
            var journal = journalResolution.journal();
            AgentFleet fleet = new DefaultAgentFleet(proxies, evaluators,
                    DefaultAgentFleet.DEFAULT_PARALLEL_TIMEOUT_MS, activityListeners);
            if (journal != CoordinationJournal.NOOP) {
                // Externally-managed journals (e.g. Spring-wired beans) own
                // their own lifecycle — calling start() here would re-start
                // the underlying CheckpointStore and double-log on boot.
                if (!journalResolution.externallyManaged()) {
                    journal.start();
                    // Track the processor-owned journal + install a one-shot
                    // framework shutdown listener so stop() is called on
                    // framework destroy(). Without this, ServiceLoader-wired
                    // journals with real resources (JDBC, file handles,
                    // background flushers) leak on every non-Spring shutdown.
                    ownedJournals.add(new OwnedJournal(journal));
                    installShutdownHook(framework);
                }
                fleet = new JournalingAgentFleet(fleet, journal, coordinatorName);
            }
            var responseType = annotation.responseAs() == Void.class
                    ? null : annotation.responseAs();
            var journalHook = resolveJournalHook(annotation, fleet, journal);
            var injectables = new LinkedHashMap<Class<?>, Object>();
            injectables.put(AgentFleet.class, fleet);
            if (responseType != null) {
                injectables.put(Class.class, responseType);
            }
            if (journalHook != null) {
                injectables.put(org.atmosphere.ai.PostPromptHook.class, journalHook);
            }
            // v0.5 foundation primitives — wire AgentState / AgentIdentity /
            // AgentWorkspace so @Prompt / @AiTool methods on a @Coordinator
            // receive them as typed parameters. Same semantics as
            // AgentProcessor.buildFoundationPrimitives.
            wireFoundationPrimitives(coordinatorName, injectables);
            var aiHandler = new AiEndpointHandler(
                    promptTarget, promptMethod, 120_000L,
                    systemPrompt, path, runtime, List.of(),
                    memory, lifecycle, toolRegistry,
                    List.of(), List.of(), metrics, List.of(), null, injectables);

            // Step 9: Register handler
            var commandRouter = new CommandRouter(commandRegistry, instance);
            var handler = new AgentHandler(aiHandler, commandRouter,
                    instance, framework.getAtmosphereConfig());
            handler.setAgentName(coordinatorName);

            List<AtmosphereInterceptor> interceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, interceptors);
            framework.addAtmosphereHandler(path, handler, interceptors);

            // Step 10: Register protocol bridges
            var protocols = new ArrayList<String>();
            var model = settings != null ? settings.model() : null;
            AiPipeline pipeline = null;
            if (runtime != null) {
                pipeline = new AiPipeline(runtime, systemPrompt, model, memory,
                        toolRegistry, List.of(), List.of(), metrics);
            } else {
                logger.warn("Coordinator '{}': no AgentRuntime on classpath — "
                        + "session.stream() will buffer text instead of invoking LLM",
                        coordinatorName);
            }
            var guardrails = skillFile != null ? skillFile.listItems("Guardrails") : List.<String>of();
            var channels = skillFile != null ? skillFile.listItems("Channels") : List.<String>of();
            registerA2a(framework, annotation,
                    promptTarget, promptMethod, fleet,
                    pipeline, path, guardrails, protocols);
            registerMcp(framework, annotation, toolRegistry, path, guardrails, protocols);
            registerAgUi(framework, promptTarget, promptMethod, path,
                    pipeline, fleet, protocols);
            wireChannelBridge(coordinatorName, commandRouter, instance, systemPrompt,
                    pipeline, channels, protocols);

            // Step 11: Log fleet topology
            logTopology(coordinatorName, path, annotation.version(), proxies,
                    fleetAnnotation, protocols);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to register Coordinator from " + annotatedClass.getName(), e);
        }
    }

    // --- Fleet resolution ---

    private LinkedHashMap<String, AgentProxy> resolveFleet(
            AtmosphereFramework framework, Fleet fleetAnnotation,
            String coordinatorName, List<AgentActivityListener> activityListeners) {
        var proxies = new LinkedHashMap<String, AgentProxy>();
        var seenNames = new HashSet<String>();

        for (var ref : fleetAnnotation.value()) {
            var agentName = resolveAgentName(ref, coordinatorName);
            if (!seenNames.add(agentName)) {
                throw new IllegalStateException("Duplicate agent reference '"
                        + agentName + "' in coordinator '" + coordinatorName + "'");
            }

            var transport = resolveTransport(framework, agentName, ref);
            var version = ref.version().isEmpty() ? "1.0.0" : ref.version();
            var isLocal = transport instanceof LocalAgentTransport;
            var limits = ref.timeoutMs() > 0
                    ? AgentLimits.withTimeout(java.time.Duration.ofMillis(ref.timeoutMs()))
                    : AgentLimits.DEFAULT;

            AgentProxy proxy = new DefaultAgentProxy(
                    agentName, version, ref.weight(), isLocal,
                    ref.maxRetries(), transport, activityListeners, limits);
            if (ref.circuitBreaker()) {
                proxy = new ResilientAgentProxy(proxy,
                        new DefaultCircuitBreaker(), activityListeners);
            }
            proxies.put(agentName, proxy);

            if (!transport.isAvailable() && ref.required()) {
                logger.warn("Coordinator '{}': required agent '{}' not yet available",
                        coordinatorName, agentName);
            }
        }
        return proxies;
    }

    static String resolveAgentName(AgentRef ref, String coordinatorName) {
        if (ref.type() != void.class) {
            var agentAnn = ref.type().getAnnotation(Agent.class);
            if (agentAnn != null) {
                return agentAnn.name();
            }
            var coordAnn = ref.type().getAnnotation(Coordinator.class);
            if (coordAnn != null) {
                return coordAnn.name();
            }
            throw new IllegalStateException("Coordinator '" + coordinatorName
                    + "': @AgentRef type " + ref.type().getName()
                    + " has neither @Agent nor @Coordinator");
        }
        if (!ref.value().isEmpty()) {
            return ref.value();
        }
        throw new IllegalStateException("Coordinator '" + coordinatorName
                + "': @AgentRef must specify either value() or type()");
    }

    private AgentTransport resolveTransport(AtmosphereFramework framework,
                                            String agentName, AgentRef ref) {
        // Build candidate paths — default + custom endpoint from @Agent annotation
        var defaultPath = "/atmosphere/agent/" + agentName + "/a2a";
        var altPath = "/atmosphere/a2a/" + agentName;

        // If we have the class ref, read the custom endpoint from @Agent(endpoint=...)
        String customEndpoint = null;
        if (ref.type() != void.class) {
            var agentAnn = ref.type().getAnnotation(Agent.class);
            if (agentAnn != null && !agentAnn.endpoint().isEmpty()) {
                customEndpoint = agentAnn.endpoint();
            }
        }

        // Check for explicit remote URL FIRST — env var, system property, or
        // init parameter. When set, the URL overrides local transport even if
        // the agent is in the same JVM. This lets deployments force A2A protocol.
        var envKey = "AGENT_" + agentName.toUpperCase().replace('-', '_') + "_URL";
        var remoteUrl = System.getenv(envKey);
        if (remoteUrl == null) {
            remoteUrl = System.getProperty(
                    "atmosphere.fleet.agents." + agentName + ".url");
        }
        if (remoteUrl == null) {
            remoteUrl = framework.getAtmosphereConfig().getInitParameter(
                    "atmosphere.fleet.agents." + agentName + ".url");
        }
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            var timeouts = remoteUrl.contains("localhost") || remoteUrl.contains("127.0.0.1")
                    ? A2aAgentTransport.Timeouts.CO_LOCATED
                    : A2aAgentTransport.Timeouts.DEFAULT;
            return new A2aAgentTransport(agentName, remoteUrl, timeouts);
        }

        // No explicit URL — always construct a LocalAgentTransport that
        // re-queries the framework handler map on every call. This closes the
        // startup race where the agent bean's A2A handler registers after the
        // coordinator wires its fleet: the transport probes the full candidate
        // path list lazily, so whichever variant the handler ultimately lands
        // at is discovered on the first dispatch without restart. The order
        // matters — customEndpoint wins over the conventional defaults when
        // the @Agent annotation pins one.
        var candidates = new ArrayList<String>(3);
        if (customEndpoint != null) {
            candidates.add(customEndpoint);
        }
        candidates.add(defaultPath);
        candidates.add(altPath);
        return new LocalAgentTransport(framework, agentName, candidates);
    }

    // --- Circular dependency detection ---

    private void detectCircularDependencies(String coordinatorName,
                                            Set<String> agentNames) {
        dependencyGraph.put(coordinatorName, agentNames);
        var visited = new HashSet<String>();
        var inStack = new HashSet<String>();
        var path = new ArrayList<String>();
        if (hasCycle(coordinatorName, visited, inStack, path)) {
            throw new IllegalStateException(
                    "Circular fleet dependency: " + String.join(" -> ", path));
        }
    }

    private boolean hasCycle(String node, Set<String> visited,
                             Set<String> inStack, List<String> path) {
        visited.add(node);
        inStack.add(node);
        path.add(node);

        var deps = dependencyGraph.get(node);
        if (deps != null) {
            for (var dep : deps) {
                if (!visited.contains(dep)) {
                    if (dependencyGraph.containsKey(dep)
                            && hasCycle(dep, visited, inStack, path)) {
                        return true;
                    }
                } else if (inStack.contains(dep)) {
                    path.add(dep);
                    return true;
                }
            }
        }

        inStack.remove(node);
        path.removeLast();
        return false;
    }

    // --- Topology logging ---

    private void logTopology(String coordinatorName, String endpointPath, String version,
                             Map<String, AgentProxy> proxies,
                             Fleet fleetAnnotation, List<String> protocols) {
        var refMap = new LinkedHashMap<String, AgentRef>();
        for (var ref : fleetAnnotation.value()) {
            refMap.put(resolveAgentName(ref, coordinatorName), ref);
        }

        var sb = new StringBuilder();
        sb.append(String.format(
                "Coordinator '%s' registered at %s (v%s, fleet: %d agents, protocols: %s)%n",
                coordinatorName, endpointPath, version, proxies.size(),
                protocols.isEmpty() ? "[web]" : protocols));
        sb.append(String.format("  %s (v%s)%n", coordinatorName, version));

        for (var entry : proxies.entrySet()) {
            var proxy = entry.getValue();
            var ref = refMap.get(entry.getKey());
            var typeInfo = ref != null && ref.type() != void.class
                    ? "  [" + ref.type().getSimpleName() + "]" : "";
            sb.append(String.format("  +-- %-15s (%s, v%s, weight=%d, %s)%s%n",
                    proxy.name(),
                    proxy.isLocal() ? "local" : "remote",
                    proxy.version(),
                    proxy.weight(),
                    ref != null && ref.required() ? "required" : "optional",
                    typeInfo));
        }
        logger.info(sb.toString().stripTrailing());
    }

    // --- AI infrastructure (public APIs from atmosphere-ai) ---

    private AiConfig.LlmSettings resolveSettings() {
        var config = AiConfig.get();
        return config != null ? config : AiConfig.fromEnvironment();
    }

    private AgentRuntime resolveRuntime(AiConfig.LlmSettings settings) {
        try {
            var all = AgentRuntimeResolver.resolveAll();
            if (all.isEmpty()) {
                logger.warn("No AgentRuntime found on classpath");
                return null;
            }
            var rt = all.getFirst();
            if (settings != null) {
                rt.configure(settings);
            }
            return rt;
        } catch (Exception | ServiceConfigurationError e) {
            logger.warn("Failed to resolve AgentRuntime: {}", e.getMessage());
            return null;
        }
    }

    private AiConversationMemory resolveMemory(int maxHistory) {
        try {
            var persistence = ServiceLoader.load(ConversationPersistence.class)
                    .findFirst().orElse(null);
            if (persistence != null) {
                return new PersistentConversationMemory(persistence, maxHistory);
            }
        } catch (Exception | ServiceConfigurationError e) {
            logger.debug("No ConversationPersistence provider: {}", e.getMessage());
        }
        return new InMemoryConversationMemory(maxHistory);
    }

    private ToolRegistry registerTools(Object instance) {
        var registry = new DefaultToolRegistry();
        for (var method : instance.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(AiTool.class)) {
                registry.register(instance);
                break;
            }
        }
        return registry;
    }

    private AiMetrics resolveMetrics() {
        try {
            return ServiceLoader.load(AiMetrics.class)
                    .findFirst().orElse(AiMetrics.NOOP);
        } catch (Exception | ServiceConfigurationError e) {
            logger.debug("No AiMetrics provider available: {}", e.getMessage());
            return AiMetrics.NOOP;
        }
    }

    private org.atmosphere.ai.PostPromptHook resolveJournalHook(
            Coordinator annotation, AgentFleet fleet, CoordinationJournal journal) {
        var formatClass = annotation.journalFormat();
        if (formatClass == JournalFormat.class || journal == CoordinationJournal.NOOP) {
            return null;
        }
        try {
            var format = formatClass.getDeclaredConstructor().newInstance();
            return session -> {
                var log = fleet.journal().formatLog(format);
                session.emit(new org.atmosphere.ai.AiEvent.ToolStart(
                        "coordination_journal", Map.of("query", "all events")));
                session.emit(new org.atmosphere.ai.AiEvent.ToolResult(
                        "coordination_journal", log));
            };
        } catch (ReflectiveOperationException e) {
            logger.warn("Failed to instantiate JournalFormat '{}': {}",
                    formatClass.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Result of journal resolution: the journal itself plus a flag indicating
     * whether the processor owns its lifecycle (i.e. should call
     * {@code start()}/{@code stop()}) or not.
     *
     * <p>Externally-managed journals come from the framework property
     * {@link #COORDINATION_JOURNAL_PROPERTY}; built-in resolution via
     * {@link java.util.ServiceLoader} is processor-owned because nothing else
     * starts the discovered provider.</p>
     */
    record ResolvedJournal(CoordinationJournal journal, boolean externallyManaged) {}

    /**
     * Handle for a processor-owned {@link CoordinationJournal}. The
     * {@link #stopped} flag is a close-once CAS: whichever caller flips it
     * from {@code false} to {@code true} is responsible for invoking
     * {@link CoordinationJournal#stop() stop()} exactly once. Re-entrant
     * shutdown attempts and double-destroy sequences become no-ops.
     */
    static final class OwnedJournal {
        final CoordinationJournal journal;
        final AtomicBoolean stopped = new AtomicBoolean(false);

        OwnedJournal(CoordinationJournal journal) {
            this.journal = journal;
        }
    }

    /**
     * Registers a one-shot framework shutdown listener that calls
     * {@link CoordinationJournal#stop() stop()} on every processor-owned
     * journal. Idempotent per-framework: multiple {@code @Coordinator}
     * classes share a processor instance (processors are cached in
     * {@code AnnotationHandler#processors}) so we must not register a
     * duplicate listener for each coordinator found in the same scan.
     *
     * <p>The listener is installed on the first ServiceLoader-started journal
     * we observe. The shutdown path iterates {@link #ownedJournals} at
     * fire time, so journals added for subsequent coordinators (after the
     * listener is already installed) are still stopped correctly.</p>
     */
    private void installShutdownHook(AtmosphereFramework framework) {
        synchronized (shutdownHookInstalledOn) {
            if (!shutdownHookInstalledOn.add(framework)) {
                return;
            }
        }
        framework.frameworkListener(new AtmosphereFrameworkListenerAdapter() {
            @Override
            public void onPreDestroy(AtmosphereFramework f) {
                stopOwnedJournals();
            }
        });
    }

    /**
     * Test hook: simulate the bookkeeping the {@link #handle} path performs
     * when it successfully starts a ServiceLoader-wired journal. Tests use
     * this to drive the shutdown path without running the full annotation
     * scan. Not part of the public API.
     */
    void trackOwnedJournalForTest(AtmosphereFramework framework,
                                  CoordinationJournal journal) {
        ownedJournals.add(new OwnedJournal(journal));
        installShutdownHook(framework);
    }

    /**
     * Stops every processor-owned journal that was started on the
     * {@link java.util.ServiceLoader} path. Called from the framework
     * {@link org.atmosphere.cpr.AtmosphereFrameworkListener#onPreDestroy
     * onPreDestroy} hook and safe to call repeatedly: the per-journal
     * {@link OwnedJournal#stopped} CAS guarantees {@code stop()} fires at
     * most once per journal. Exceptions thrown by a single journal are
     * logged at WARN and do not abort the loop — a single misbehaving
     * journal must not prevent the framework from shutting down cleanly or
     * block sibling journals from releasing their resources.
     *
     * <p>Package-private for direct exercise from
     * {@code CoordinatorProcessorJournalTest}.</p>
     */
    void stopOwnedJournals() {
        for (var owned : ownedJournals) {
            if (!owned.stopped.compareAndSet(false, true)) {
                continue;
            }
            try {
                owned.journal.stop();
            } catch (RuntimeException e) {
                logger.warn("CoordinationJournal {} failed to stop on framework "
                                + "shutdown; resources may leak: {}",
                        owned.journal.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Resolves the {@link CoordinationJournal} for this coordinator. Resolution
     * order:
     * <ol>
     *   <li>Bean bridged via {@link #COORDINATION_JOURNAL_PROPERTY} on
     *       {@code framework.getAtmosphereConfig().properties()} — this is how
     *       Spring Boot's auto-configuration injects a Spring-managed journal
     *       bean. The processor does <strong>not</strong> own its lifecycle.</li>
     *   <li>{@link java.util.ServiceLoader} provider on the classpath. The
     *       processor owns the lifecycle of providers it discovers this way.</li>
     *   <li>{@link CoordinationJournal#NOOP NOOP} fallback.</li>
     * </ol>
     */
    ResolvedJournal resolveJournal(AtmosphereFramework framework) {
        // 1. Externally-bridged (Spring bean, manual setProperty, etc.).
        var bridged = framework.getAtmosphereConfig()
                .properties().get(COORDINATION_JOURNAL_PROPERTY);
        if (bridged instanceof CoordinationJournal cj && cj != CoordinationJournal.NOOP) {
            logger.info("CoordinationJournal: {} (externally managed)",
                    cj.getClass().getName());
            return new ResolvedJournal(cj, true);
        }
        // 2. ServiceLoader fallback for plain-servlet / Quarkus deployments.
        try {
            var journal = ServiceLoader.load(CoordinationJournal.class)
                    .findFirst().orElse(CoordinationJournal.NOOP);
            if (journal != CoordinationJournal.NOOP) {
                logger.info("CoordinationJournal: {}", journal.getClass().getName());
            }
            return new ResolvedJournal(journal, false);
        } catch (Exception | ServiceConfigurationError e) {
            logger.warn("Failed to load CoordinationJournal provider: {}", e.getMessage(), e);
            return new ResolvedJournal(CoordinationJournal.NOOP, false);
        }
    }

    private List<ResultEvaluator> resolveEvaluators() {
        var evaluators = new ArrayList<ResultEvaluator>();
        try {
            ServiceLoader.load(ResultEvaluator.class).forEach(evaluators::add);
        } catch (Exception | ServiceConfigurationError e) {
            logger.debug("No ResultEvaluator providers: {}", e.getMessage());
        }
        return evaluators;
    }

    private List<AgentActivityListener> resolveActivityListeners() {
        var listeners = new ArrayList<AgentActivityListener>();
        try {
            ServiceLoader.load(AgentActivityListener.class).forEach(listeners::add);
        } catch (Exception | ServiceConfigurationError e) {
            logger.debug("No AgentActivityListener providers: {}", e.getMessage());
        }
        return listeners;
    }

    // --- Prompt method resolution ---

    private Method findPromptMethod(Class<?> clazz) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                return method;
            }
        }
        try {
            return SyntheticPrompt.class.getDeclaredMethod(
                    "onPrompt", String.class, StreamingSession.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Synthetic prompt method not found", e);
        }
    }

    static class SyntheticPrompt {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    // --- Skill file loading ---

    private SkillFileParser loadSkillFile(String skillFilePath,
                                          String coordinatorName) {
        if (skillFilePath == null || skillFilePath.isBlank()) {
            var candidates = List.of(
                    "META-INF/skills/" + coordinatorName + "/SKILL.md",
                    "prompts/" + coordinatorName + ".md",
                    "prompts/" + coordinatorName + "-skill.md"
            );
            for (var candidate : candidates) {
                var content = loadClasspathResource(candidate);
                if (content != null) {
                    return SkillFileParser.parse(content);
                }
            }
            return null;
        }
        // skill: prefix loads from atmosphere-skills repo (classpath -> cache -> GitHub)
        var content = org.atmosphere.ai.PromptLoader.resolve(skillFilePath);
        if (content == null) {
            logger.warn("Skill file '{}' not found for coordinator '{}'",
                    skillFilePath, coordinatorName);
            return null;
        }
        return SkillFileParser.parse(content);
    }

    private String loadClasspathResource(String path) {
        try (var is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (is == null) {
                return null;
            }
            try (var reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // --- Protocol bridge registration ---

    private void registerA2a(AtmosphereFramework framework, Coordinator annotation,
                             Object promptTarget,
                             Method promptMethod, AgentFleet fleet,
                             AiPipeline pipeline, String basePath,
                             List<String> guardrails, List<String> protocols) {
        if (!ClasspathDetector.hasA2a()) {
            return;
        }
        try {
            var registry = new org.atmosphere.a2a.registry.A2aRegistry();

            // Register a bridge that properly handles the coordinator's @Prompt
            // signature (String, AgentFleet, StreamingSession) by injecting the
            // fleet and a collecting StreamingSession
            var bridge = new A2aCoordinatorBridge(promptTarget, promptMethod, fleet);
            bridge.setPipeline(pipeline);
            var handleMethod = A2aCoordinatorBridge.class.getDeclaredMethod(
                    "handlePrompt",
                    org.atmosphere.a2a.runtime.TaskContext.class, String.class);
            registry.registerSkill("chat", "Chat",
                    "Natural language conversation",
                    List.of("chat", "nl"), handleMethod, bridge,
                    List.of(new org.atmosphere.a2a.registry.A2aRegistry.ParamEntry(
                            "message", "The message to process", true, String.class)));

            var a2aEndpoint = basePath + "/a2a";
            var description = annotation.description().isEmpty()
                    ? "Coordinator: " + annotation.name()
                    : annotation.description();
            var card = registry.buildAgentCard(annotation.name(), description,
                    annotation.version(), a2aEndpoint,
                    guardrails.isEmpty() ? null : guardrails);
            var taskManager = new org.atmosphere.a2a.runtime.TaskManager();
            var protocolHandler =
                    new org.atmosphere.a2a.runtime.A2aProtocolHandler(
                            registry, taskManager, card);
            var a2aHandler =
                    new org.atmosphere.a2a.runtime.A2aHandler(protocolHandler);

            framework.addAtmosphereHandler(a2aEndpoint, a2aHandler,
                    new ArrayList<>());
            protocols.add("a2a");
        } catch (Exception e) {
            logger.warn("Failed to register A2A for coordinator '{}'",
                    annotation.name(), e);
        }
    }

    /**
     * Registers the coordinator's {@code @AiTool} methods as MCP tools.
     * The MCP endpoint is registered at {@code basePath + "/mcp"}.
     *
     * <p>All MCP type references live in {@code McpCoordinatorRegistration},
     * which is loaded reflectively only when {@link ClasspathDetector#hasMcp()}
     * succeeds — so samples that depend on {@code atmosphere-coordinator}
     * without the optional {@code atmosphere-mcp} dependency never link any
     * MCP symbols.</p>
     */
    private void registerMcp(AtmosphereFramework framework, Coordinator annotation,
                              ToolRegistry toolRegistry, String basePath,
                              List<String> guardrails, List<String> protocols) {
        if (!ClasspathDetector.hasMcp()) {
            return;
        }
        try {
            // Load the MCP bridge reflectively so CoordinatorProcessor.class
            // never carries symbolic references to org.atmosphere.mcp.* —
            // otherwise linking this class would NoClassDefFoundError on
            // samples that omit the optional atmosphere-mcp dependency.
            var bridge = Class.forName(
                    "org.atmosphere.coordinator.processor.McpCoordinatorRegistration",
                    true, Thread.currentThread().getContextClassLoader());
            var register = bridge.getDeclaredMethod("register",
                    AtmosphereFramework.class, String.class, String.class,
                    ToolRegistry.class, String.class,
                    List.class, List.class);
            register.setAccessible(true);
            register.invoke(null, framework, annotation.name(), annotation.version(),
                    toolRegistry, basePath + "/mcp", guardrails, protocols);
        } catch (ClassNotFoundException e) {
            logger.debug("MCP bridge class not loadable; skipping MCP registration for coordinator '{}'",
                    annotation.name());
        } catch (ReflectiveOperationException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Failed to register MCP endpoint for coordinator '{}': {}",
                    annotation.name(), cause.getMessage());
        }
    }

    /**
     * Registers an AG-UI endpoint that bridges the coordinator's {@code @Prompt}
     * method into the AG-UI SSE protocol. The endpoint is registered at
     * {@code basePath + "/agui"}.
     */
    private void registerAgUi(AtmosphereFramework framework,
                               Object promptTarget, Method promptMethod,
                               String basePath, AiPipeline pipeline,
                               AgentFleet fleet, List<String> protocols) {
        if (!ClasspathDetector.hasAgUi()) {
            return;
        }
        try {
            var bridge = new AgUiCoordinatorBridge(promptTarget, promptMethod, fleet);
            var actionMethod = AgUiCoordinatorBridge.class.getDeclaredMethod(
                    "onAction",
                    org.atmosphere.agui.runtime.RunContext.class,
                    StreamingSession.class);

            var handler = new org.atmosphere.agui.runtime.AgUiHandler(
                    bridge, actionMethod, pipeline);
            framework.addAtmosphereHandler(basePath + "/agui", handler, new ArrayList<>());
            protocols.add("ag-ui");
            logger.debug("AG-UI endpoint registered at {}/agui", basePath);
        } catch (Exception e) {
            logger.warn("Failed to register AG-UI endpoint for coordinator: {}",
                    e.getMessage());
        }
    }

    /**
     * Bridge between the coordinator's {@code @Prompt} method and the AG-UI
     * {@link org.atmosphere.agui.runtime.RunContext}-based action protocol.
     */
    static class AgUiCoordinatorBridge {
        private final Object promptTarget;
        private final Method bridgedPromptMethod;
        private final AgentFleet fleet;

        AgUiCoordinatorBridge(Object promptTarget, Method promptMethod,
                              AgentFleet fleet) {
            this.promptTarget = promptTarget;
            this.bridgedPromptMethod = promptMethod;
            this.fleet = fleet;
        }

        @SuppressWarnings("unused") // invoked reflectively by AgUiHandler
        public void onAction(org.atmosphere.agui.runtime.RunContext context,
                             StreamingSession session) {
            var message = context.lastUserMessage();
            if (message != null && !message.isBlank()) {
                try {
                    var paramTypes = bridgedPromptMethod.getParameterTypes();
                    var args = new Object[paramTypes.length];
                    for (var i = 0; i < paramTypes.length; i++) {
                        if (paramTypes[i] == String.class) {
                            args[i] = message;
                        } else if (StreamingSession.class.isAssignableFrom(paramTypes[i])) {
                            args[i] = session;
                        } else if (AgentFleet.class.isAssignableFrom(paramTypes[i])) {
                            args[i] = fleet;
                        }
                    }
                    bridgedPromptMethod.invoke(promptTarget, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    session.error(e.getCause() != null ? e.getCause() : e);
                } catch (Exception e) {
                    session.error(e);
                }
            } else {
                session.complete();
            }
        }
    }

    /**
     * Bridge between the coordinator's {@code @Prompt} method and the A2A
     * protocol. Handles the coordinator's prompt signature by injecting
     * the {@link AgentFleet} and a collecting {@link StreamingSession}.
     */
    static class A2aCoordinatorBridge {
        private final Object promptTarget;
        private final Method bridgedPromptMethod;
        private final AgentFleet fleet;
        private volatile AiPipeline pipeline;

        A2aCoordinatorBridge(Object promptTarget, Method promptMethod,
                             AgentFleet fleet) {
            this.promptTarget = promptTarget;
            this.bridgedPromptMethod = promptMethod;
            this.fleet = fleet;
        }

        void setPipeline(AiPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @SuppressWarnings("unused") // invoked reflectively by A2aProtocolHandler
        public void handlePrompt(org.atmosphere.a2a.runtime.TaskContext taskCtx,
                                 String message) {
            if (message == null || message.isBlank()) {
                taskCtx.fail("Empty message");
                return;
            }

            // Fast-path: A2A protocol approval responses ("/__approval/<id>/approve")
            // route through the pipeline's ApprovalRegistry so the parked VT from
            // a previous @RequiresApproval tool call resumes. Without this, every
            // approval-shaped message on A2A would be dispatched as a new prompt
            // and the waiting VT would time out.
            if (pipeline != null
                    && org.atmosphere.ai.approval.ApprovalRegistry.isApprovalMessage(message)
                    && pipeline.tryResolveApproval(message)) {
                taskCtx.complete("");
                return;
            }

            try {
                bridgedPromptMethod.setAccessible(true);
                var collector = new A2aCoordinatorCollector(taskCtx, pipeline);
                var paramTypes = bridgedPromptMethod.getParameterTypes();
                var args = new Object[paramTypes.length];
                for (var i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i] == String.class) {
                        args[i] = message;
                    } else if (StreamingSession.class.isAssignableFrom(paramTypes[i])) {
                        args[i] = collector;
                    } else if (AgentFleet.class.isAssignableFrom(paramTypes[i])) {
                        args[i] = fleet;
                    }
                }
                bridgedPromptMethod.invoke(promptTarget, args);
                collector.awaitAndFinalize(120_000L);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause() != null ? e.getCause() : e;
                taskCtx.fail(cause.getMessage());
            } catch (Exception e) {
                taskCtx.fail(e.getMessage());
            }
        }
    }

    /**
     * Thin nested alias over
     * {@link org.atmosphere.a2a.runtime.A2aStreamCollector}, kept only so
     * call sites inside this file can write {@code new A2aCoordinatorCollector(...)}
     * without importing the fully-qualified shared base. The real concurrency,
     * finalization, and error semantics live in the shared base — previously
     * this class and {@code AgentProcessor.A2aStreamCollector} were
     * copy-paste clones with divergent thread-safety.
     */
    static final class A2aCoordinatorCollector extends org.atmosphere.a2a.runtime.A2aStreamCollector {
        A2aCoordinatorCollector(org.atmosphere.a2a.runtime.TaskContext taskCtx,
                                AiPipeline pipeline) {
            super(taskCtx, pipeline);
        }
    }

    /**
     * Wire the v0.5 foundation primitives (AgentState, AgentIdentity,
     * AgentWorkspace) into the @Coordinator's injectables so @Prompt /
     * @AiTool methods can declare the SPI types as parameters. Same
     * per-agent-root layout as {@code AgentProcessor.buildFoundationPrimitives}
     * and non-fatal on failure — the coordinator still boots, the missing
     * primitive simply won't be injectable.
     */
    private void wireFoundationPrimitives(String coordinatorName,
                                          java.util.Map<Class<?>, Object> injectables) {
        var workspaceRoot = System.getProperty("atmosphere.workspace.root");
        if (workspaceRoot == null) {
            workspaceRoot = System.getenv("ATMOSPHERE_WORKSPACE_ROOT");
        }
        if (workspaceRoot == null) {
            workspaceRoot = System.getProperty("user.home") + "/.atmosphere/workspace";
        }
        try {
            var root = java.nio.file.Path.of(workspaceRoot)
                    .resolve("coordinators").resolve(coordinatorName);
            java.nio.file.Files.createDirectories(root);
            var state = new org.atmosphere.ai.state.FileSystemAgentState(root);
            injectables.put(org.atmosphere.ai.state.AgentState.class, state);
            injectables.put(org.atmosphere.ai.state.FileSystemAgentState.class, state);
        } catch (Exception e) {
            logger.warn("AgentState not wired for coordinator '{}': {}",
                    coordinatorName, e.getMessage());
        }

        try {
            var credentials = org.atmosphere.ai.identity.AtmosphereEncryptedCredentialStore
                    .withFreshKey();
            var identity = new org.atmosphere.ai.identity.InMemoryAgentIdentity(credentials);
            injectables.put(org.atmosphere.ai.identity.AgentIdentity.class, identity);
            injectables.put(org.atmosphere.ai.identity.CredentialStore.class, credentials);
        } catch (Exception e) {
            logger.warn("AgentIdentity not wired for coordinator '{}': {}",
                    coordinatorName, e.getMessage());
        }

        try {
            var loader = new org.atmosphere.ai.workspace.AgentWorkspaceLoader();
            var adapters = loader.adapters();
            if (!adapters.isEmpty()) {
                injectables.put(org.atmosphere.ai.workspace.AgentWorkspace.class,
                        adapters.get(0));
            }
        } catch (Exception e) {
            logger.warn("AgentWorkspace not wired for coordinator '{}': {}",
                    coordinatorName, e.getMessage());
        }
    }

    private void wireChannelBridge(String coordinatorName,
                                   CommandRouter commandRouter, Object instance,
                                   String systemPrompt, AiPipeline pipeline,
                                   List<String> channels, List<String> protocols) {
        if (!ClasspathDetector.hasChannels()) {
            return;
        }
        try {
            var bridgeClass = Class.forName(
                    "org.atmosphere.channels.ChannelAiBridge");
            var registerMethod = bridgeClass.getMethod("registerAgent",
                    String.class, Object.class, Object.class,
                    String.class, Object.class, List.class);
            registerMethod.invoke(null, coordinatorName, commandRouter,
                    instance, systemPrompt, pipeline, channels);
            protocols.add("channels");
        } catch (ClassNotFoundException ex) {
            logger.trace("Channels not available", ex);
        } catch (Exception e) {
            logger.warn("Failed to wire channel bridge for coordinator '{}'",
                    coordinatorName, e);
        }
    }
}
