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
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.ConversationPersistence;
import org.atmosphere.ai.DefaultAiSupportResolver;
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
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.fleet.DefaultAgentFleet;
import org.atmosphere.coordinator.fleet.DefaultAgentProxy;
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
@AtmosphereAnnotation(Coordinator.class)
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
            var aiSupport = resolveAiSupport(settings);
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
            var fleet = new DefaultAgentFleet(proxies);
            var injectables = Map.<Class<?>, Object>of(AgentFleet.class, fleet);
            var aiHandler = new AiEndpointHandler(
                    promptTarget, promptMethod, 120_000L,
                    systemPrompt, path, aiSupport, List.of(),
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
            registerA2a(framework, annotation, commandRegistry, toolRegistry,
                    commandRouter, promptTarget, promptMethod, path, protocols);
            var model = settings != null ? settings.model() : null;
            var pipeline = new AiPipeline(aiSupport, systemPrompt, model, memory,
                    toolRegistry, List.of(), List.of(), metrics);
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
            var agentName = resolveAgentName(ref);
            if (!seenNames.add(agentName)) {
                throw new IllegalStateException("Duplicate agent reference '"
                        + agentName + "' in coordinator '" + coordinatorName + "'");
            }

            var transport = resolveTransport(framework, agentName, ref);
            var version = ref.version().isEmpty() ? "1.0.0" : ref.version();
            var isLocal = transport instanceof LocalAgentTransport;

            proxies.put(agentName, new DefaultAgentProxy(
                    agentName, version, ref.weight(), isLocal, transport));

            if (!transport.isAvailable() && ref.required()) {
                logger.warn("Coordinator '{}': required agent '{}' not yet available",
                        coordinatorName, agentName);
            }
        }
        return proxies;
    }

    static String resolveAgentName(AgentRef ref) {
        if (ref.type() != void.class) {
            var agentAnn = ref.type().getAnnotation(Agent.class);
            if (agentAnn != null) {
                return agentAnn.name();
            }
            var coordAnn = ref.type().getAnnotation(Coordinator.class);
            if (coordAnn != null) {
                return coordAnn.name();
            }
            throw new IllegalStateException("@AgentRef type "
                    + ref.type().getName() + " has neither @Agent nor @Coordinator");
        }
        if (!ref.value().isEmpty()) {
            return ref.value();
        }
        throw new IllegalStateException(
                "@AgentRef must specify either value() or type()");
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
            refMap.put(resolveAgentName(ref), ref);
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

    private AiSupport resolveAiSupport(AiConfig.LlmSettings settings) {
        var all = DefaultAiSupportResolver.resolveAll();
        if (all.isEmpty()) {
            logger.warn("No AiSupport implementation found on classpath");
            return null;
        }
        var support = all.getFirst();
        if (settings != null) {
            support.configure(settings);
        }
        return support;
    }

    private AiConversationMemory resolveMemory(int maxHistory) {
        var persistence = ServiceLoader.load(ConversationPersistence.class)
                .findFirst().orElse(null);
        if (persistence != null) {
            return new PersistentConversationMemory(persistence, maxHistory);
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
                             Method promptMethod, String basePath,
                             List<String> protocols) {
        if (!ClasspathDetector.hasA2a()) {
            return;
        }
        try {
            var registry = new org.atmosphere.a2a.registry.A2aRegistry();

            // Register default NL skill
            registry.registerSkill("chat", "Chat",
                    "Natural language conversation",
                    List.of("chat", "nl"), promptMethod, promptTarget, List.of());

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
        } catch (ClassNotFoundException ignored) {
            // Channels not available
        } catch (Exception e) {
            logger.warn("Failed to wire channel bridge for coordinator '{}'",
                    coordinatorName, e);
        }
    }
}
