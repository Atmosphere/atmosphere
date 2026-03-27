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
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.JournalingAgentFleet;
import org.atmosphere.coordinator.transport.A2aAgentTransport;
import org.atmosphere.coordinator.transport.AgentTransport;
import org.atmosphere.coordinator.transport.LocalAgentTransport;
import org.atmosphere.cpr.AtmosphereFramework;
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

    private static final Map<String, Set<String>> dependencyGraph = new ConcurrentHashMap<>();

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
            var proxies = resolveFleet(framework, fleetAnnotation, coordinatorName);

            // Step 7: Validate fleet
            detectCircularDependencies(coordinatorName, proxies.keySet());

            // Step 8: Create AgentFleet and AiEndpointHandler with injectable
            var evaluators = resolveEvaluators();
            var journal = resolveJournal();
            AgentFleet fleet = new DefaultAgentFleet(proxies, evaluators);
            if (journal != CoordinationJournal.NOOP) {
                journal.start();
                fleet = new JournalingAgentFleet(fleet, journal, coordinatorName);
            }
            var responseType = annotation.responseAs() == Void.class
                    ? null : annotation.responseAs();
            var injectables = responseType != null
                    ? Map.<Class<?>, Object>of(AgentFleet.class, fleet, Class.class, responseType)
                    : Map.<Class<?>, Object>of(AgentFleet.class, fleet);
            var aiHandler = new AiEndpointHandler(
                    promptTarget, promptMethod, 120_000L,
                    systemPrompt, path, runtime, List.of(),
                    memory, lifecycle, toolRegistry,
                    List.of(), List.of(), metrics, List.of(), null, injectables);

            // Step 9: Register handler
            var commandRouter = new CommandRouter(commandRegistry, instance);
            var handler = new AgentHandler(aiHandler, commandRouter,
                    instance, framework.getAtmosphereConfig());

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
            registerA2a(framework, annotation, commandRegistry, toolRegistry,
                    commandRouter, promptTarget, promptMethod, fleet,
                    pipeline, path, protocols);
            registerMcp(framework, annotation, toolRegistry, path, protocols);
            registerAgUi(framework, promptTarget, promptMethod, path,
                    pipeline, fleet, protocols);
            wireChannelBridge(coordinatorName, commandRouter, instance, systemPrompt,
                    pipeline, protocols);

            // Step 11: Log fleet topology
            logTopology(coordinatorName, annotation.version(), proxies,
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
            String coordinatorName) {
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

            proxies.put(agentName, new DefaultAgentProxy(
                    agentName, version, ref.weight(), isLocal,
                    ref.maxRetries(), transport));

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

        // Check all candidate paths (already registered or deferred)
        for (var path : new String[]{customEndpoint, defaultPath, altPath}) {
            if (path != null && framework.getAtmosphereHandlers().containsKey(path)) {
                return new LocalAgentTransport(framework, agentName, path);
            }
        }

        // Check for remote URL via environment or system property
        var envKey = "AGENT_" + agentName.toUpperCase().replace('-', '_') + "_URL";
        var remoteUrl = System.getenv(envKey);
        if (remoteUrl == null) {
            remoteUrl = System.getProperty(
                    "atmosphere.fleet.agents." + agentName + ".url");
        }
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            return new A2aAgentTransport(agentName, remoteUrl);
        }

        // Deferred: prefer custom endpoint if known, else default
        var deferredPath = customEndpoint != null ? customEndpoint : defaultPath;
        return new LocalAgentTransport(framework, agentName, deferredPath);
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

    private void logTopology(String coordinatorName, String version,
                             Map<String, AgentProxy> proxies,
                             Fleet fleetAnnotation, List<String> protocols) {
        var refMap = new LinkedHashMap<String, AgentRef>();
        for (var ref : fleetAnnotation.value()) {
            refMap.put(resolveAgentName(ref, coordinatorName), ref);
        }

        var sb = new StringBuilder();
        sb.append(String.format(
                "Coordinator '%s' registered (v%s, fleet: %d agents, protocols: %s)%n",
                coordinatorName, version, proxies.size(),
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

    private CoordinationJournal resolveJournal() {
        try {
            return ServiceLoader.load(CoordinationJournal.class)
                    .findFirst().orElse(CoordinationJournal.NOOP);
        } catch (Exception | ServiceConfigurationError e) {
            logger.debug("No CoordinationJournal provider: {}", e.getMessage());
            return CoordinationJournal.NOOP;
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
        var content = loadClasspathResource(skillFilePath);
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
                             CommandRegistry commandRegistry, ToolRegistry toolRegistry,
                             CommandRouter commandRouter, Object promptTarget,
                             Method promptMethod, AgentFleet fleet,
                             AiPipeline pipeline, String basePath,
                             List<String> protocols) {
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
                    annotation.version(), a2aEndpoint);
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
     */
    private void registerMcp(AtmosphereFramework framework, Coordinator annotation,
                              ToolRegistry toolRegistry, String basePath,
                              List<String> protocols) {
        if (!ClasspathDetector.hasMcp()) {
            return;
        }
        try {
            var mcpRegistry = new org.atmosphere.mcp.registry.McpRegistry();

            // Bridge @AiTool methods from the ToolRegistry
            for (var tool : toolRegistry.allTools()) {
                var params = tool.parameters().stream()
                        .map(p -> new org.atmosphere.mcp.registry.McpRegistry.ParamEntry(
                                p.name(), p.description(), p.required(),
                                jsonSchemaTypeToClass(p.type())))
                        .toList();
                mcpRegistry.registerTool(tool.name(), tool.description(), params,
                        args -> tool.executor().execute(args));
            }

            var version = annotation.version();
            var protocolHandler = new org.atmosphere.mcp.runtime.McpProtocolHandler(
                    annotation.name(), version, mcpRegistry,
                    framework.getAtmosphereConfig());

            var handler = new org.atmosphere.mcp.runtime.McpHandler(protocolHandler);
            var mcpPath = basePath + "/mcp";
            framework.addAtmosphereHandler(mcpPath, handler, new ArrayList<>());
            protocols.add("mcp");
            logger.debug("MCP endpoint registered at {} with {} tools",
                    mcpPath, mcpRegistry.tools().size());
        } catch (Exception e) {
            logger.warn("Failed to register MCP endpoint for coordinator '{}': {}",
                    annotation.name(), e.getMessage());
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
     * Minimal {@link StreamingSession} adapter for A2A coordinator invocations.
     * Collects streamed text and writes it as the A2A task result on completion.
     */
    static class A2aCoordinatorCollector implements StreamingSession {
        private final org.atmosphere.a2a.runtime.TaskContext taskCtx;
        private final AiPipeline pipeline;
        private final StringBuilder buffer = new StringBuilder();
        private final java.util.concurrent.CountDownLatch completionLatch =
                new java.util.concurrent.CountDownLatch(1);
        private volatile boolean finalized;

        A2aCoordinatorCollector(org.atmosphere.a2a.runtime.TaskContext taskCtx,
                                AiPipeline pipeline) {
            this.taskCtx = taskCtx;
            this.pipeline = pipeline;
        }

        @Override public String sessionId() { return taskCtx.taskId(); }

        @Override
        public void send(String text) {
            buffer.append(text);
        }

        @Override
        public void stream(String message) {
            if (pipeline != null) {
                pipeline.execute(taskCtx.taskId(), message, this);
            } else {
                buffer.append(message);
            }
        }

        @Override public void sendMetadata(String key, Object value) { }

        @Override
        public void progress(String message) {
            taskCtx.updateStatus(org.atmosphere.a2a.types.TaskState.WORKING, message);
        }

        @Override
        public void complete() {
            if (!finalized) {
                finalized = true;
                taskCtx.complete(buffer.toString());
                completionLatch.countDown();
            }
        }

        @Override
        public void complete(String summary) {
            if (!finalized) {
                finalized = true;
                taskCtx.complete(summary != null ? summary : buffer.toString());
                completionLatch.countDown();
            }
        }

        @Override
        public void error(Throwable t) {
            if (!finalized) {
                finalized = true;
                taskCtx.fail(t.getMessage());
                completionLatch.countDown();
            }
        }

        @Override public boolean isClosed() { return finalized; }

        void awaitAndFinalize(long timeoutMs) {
            try {
                completionLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!finalized) {
                finalized = true;
                taskCtx.complete(buffer.toString());
            }
        }
    }

    private static Class<?> jsonSchemaTypeToClass(String type) {
        return switch (type) {
            case "integer" -> int.class;
            case "number" -> double.class;
            case "boolean" -> boolean.class;
            case "object" -> java.util.Map.class;
            case "array" -> java.util.List.class;
            default -> String.class;
        };
    }

    private void wireChannelBridge(String coordinatorName,
                                   CommandRouter commandRouter, Object instance,
                                   String systemPrompt, AiPipeline pipeline,
                                   List<String> protocols) {
        if (!ClasspathDetector.hasChannels()) {
            return;
        }
        try {
            var bridgeClass = Class.forName(
                    "org.atmosphere.channels.ChannelAiBridge");
            var registerMethod = bridgeClass.getMethod("registerAgent",
                    String.class, Object.class, Object.class,
                    String.class, Object.class);
            registerMethod.invoke(null, coordinatorName, commandRouter,
                    instance, systemPrompt, pipeline);
            protocols.add("channels");
        } catch (ClassNotFoundException ex) {
            logger.trace("Channels not available", ex);
        } catch (Exception e) {
            logger.warn("Failed to wire channel bridge for coordinator '{}'",
                    coordinatorName, e);
        }
    }
}
