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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.ClasspathDetector;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandResult;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.ConversationPersistence;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.PersistentConversationMemory;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.annotation.AnnotationUtil;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Annotation processor for {@link Agent}. Handles instance creation, skill file
 * parsing, command scanning, AI endpoint setup, and optional cross-protocol
 * registration (A2A, MCP, AG-UI) when the corresponding modules are on the classpath.
 */
@AtmosphereAnnotation(Agent.class)
public class AgentProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(AgentProcessor.class);
    private static final int DEFAULT_MAX_HISTORY = 20;
    private static final String AGENT_VERSION = resolveModuleVersion();

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(Agent.class);
            if (annotation == null) {
                return;
            }

            var agentName = annotation.name();
            var path = "/atmosphere/agent/" + agentName;

            // Step 1: Create instance and inject fields
            var instance = framework.newClassInstance(Object.class, annotatedClass);
            AnnotatedLifecycle.injectFields(framework, instance);

            // Headless mode: if the class has @Skill methods but no @Prompt,
            // or headless=true is set, register only protocol endpoints (no WebSocket UI).
            if (isHeadless(annotation, annotatedClass)) {
                handleHeadless(framework, annotation, instance, agentName);
                return;
            }

            // Step 2: Parse skill file → system prompt
            var skillFile = parseSkillFile(annotation);
            var systemPrompt = skillFile.systemPrompt();

            // Step 3: Scan @Command methods
            var commandRegistry = new CommandRegistry();
            commandRegistry.scan(annotatedClass);

            // Step 4: Find @Prompt or use synthetic default
            var promptMethod = findPromptMethod(annotatedClass);
            // If synthetic, the prompt target must be a SyntheticPrompt instance
            var promptTarget = promptMethod.getDeclaringClass() == SyntheticPrompt.class
                    ? new SyntheticPrompt() : instance;

            // Step 5: Resolve AI infrastructure
            var settings = resolveSettings();
            var runtime = resolveRuntime(settings);
            AiConversationMemory memory = resolveMemory(DEFAULT_MAX_HISTORY);
            var toolRegistry = registerTools(instance);

            // Warn on tool mismatch with skill file
            crossReferenceTools(skillFile, toolRegistry);

            var metrics = resolveMetrics();
            var lifecycle = AnnotatedLifecycle.scan(annotatedClass);

            // Step 6: Create AgentHandler
            var responseType = annotation.responseAs() == Void.class
                    ? null : annotation.responseAs();
            // v0.5 foundation primitives — build per-agent instances and
            // publish into injectables so @Prompt/@AiTool methods can declare
            // the SPI types (AgentState, AgentIdentity, AgentWorkspace) and
            // receive a wired instance. Previously these were aspirational in
            // the sample javadocs — nothing in production instantiated them.
            var agentInjectables = new java.util.LinkedHashMap<Class<?>, Object>();
            if (responseType != null) {
                agentInjectables.put(Class.class, responseType);
            }
            buildFoundationPrimitives(agentName, agentInjectables);
            var aiHandler = new AiEndpointHandler(
                    promptTarget, promptMethod, 120_000L,
                    systemPrompt, path, runtime, List.of(),
                    memory, lifecycle, toolRegistry,
                    List.of(), List.of(), metrics, List.of(), null, agentInjectables);

            var commandRouter = new CommandRouter(commandRegistry, instance);
            var handler = new AgentHandler(aiHandler, commandRouter,
                    instance, framework.getAtmosphereConfig());
            handler.setAgentName(agentName);

            // Step 7: Register handler at /atmosphere/agent/{name}
            List<AtmosphereInterceptor> interceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, interceptors);
            framework.addAtmosphereHandler(path, handler, interceptors);

            // Step 8-11: Optional cross-protocol registration
            var protocols = new ArrayList<String>();
            var pipeline = new org.atmosphere.ai.AiPipeline(
                    runtime, systemPrompt, settings.model(), memory,
                    toolRegistry, List.of(), List.of(), metrics);
            registerA2a(framework, annotation, skillFile, commandRegistry,
                    commandRouter, promptTarget, promptMethod, pipeline,
                    path, protocols);
            registerMcp(framework, annotation, skillFile, toolRegistry, path, protocols);
            registerAgUi(framework, promptTarget, promptMethod, path, pipeline, protocols);
            var channels = skillFile.listItems("Channels");
            wireChannelBridge(agentName, commandRouter, instance, systemPrompt, pipeline,
                    channels, protocols);

            // Step 12: Log summary
            logger.info("Agent '{}' registered at {} (class: {}, commands: {}, tools: {}, "
                            + "memory: on(max={}), protocols: {})",
                    agentName, path, annotatedClass.getSimpleName(),
                    commandRegistry.size(), toolRegistry.allTools().size(),
                    DEFAULT_MAX_HISTORY, protocols.isEmpty() ? "[web]" : protocols);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register Agent from " + annotatedClass.getName(), e);
        }
    }

    /**
     * Returns {@code true} if the agent should run headless (no WebSocket UI):
     * either explicitly set, or auto-detected when the class has skill handlers but no {@code @Prompt}.
     */
    // Package-private for testing
    boolean isHeadless(Agent annotation, Class<?> clazz) {
        if (annotation.headless()) {
            return true;
        }
        // Auto-detect: has protocol-specific methods but no @Prompt
        boolean hasPrompt = false;
        boolean hasProtocolMethods = false;
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                hasPrompt = true;
            }
            if (ClasspathDetector.hasA2a()
                    && method.isAnnotationPresent(org.atmosphere.a2a.annotation.AgentSkill.class)
                    && method.isAnnotationPresent(org.atmosphere.a2a.annotation.AgentSkillHandler.class)) {
                hasProtocolMethods = true;
            }
            if (ClasspathDetector.hasMcp() && hasMcpToolAnnotation(method)) {
                hasProtocolMethods = true;
            }
        }
        return hasProtocolMethods && !hasPrompt;
    }

    /**
     * Returns {@code true} if the given method is annotated with
     * {@code org.atmosphere.mcp.annotation.McpTool}. Resolved reflectively so
     * {@code AgentProcessor} never links against MCP types when the optional
     * {@code atmosphere-mcp} dependency is absent.
     */
    private static boolean hasMcpToolAnnotation(Method method) {
        try {
            // Class.forName on a string literal returns Class<?>; the cast to
            // Class<? extends Annotation> is unavoidable because the annotation
            // type cannot be a compile-time reference without pulling the
            // optional atmosphere-mcp jar into AgentProcessor's linkage set.
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> mcpTool =
                    (Class<? extends java.lang.annotation.Annotation>) Class.forName(
                            "org.atmosphere.mcp.annotation.McpTool",
                            false, Thread.currentThread().getContextClassLoader());
            return method.isAnnotationPresent(mcpTool);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Handles a headless agent by registering only protocol endpoints (A2A, MCP)
     * without a WebSocket UI handler. Uses the same {@link org.atmosphere.a2a.registry.A2aRegistry}
     * pattern as {@code A2aServerProcessor}.
     */
    private void handleHeadless(AtmosphereFramework framework, Agent annotation,
                                Object instance, String agentName) {
        var protocols = new ArrayList<String>();
        var basePath = "/atmosphere/agent/" + agentName;

        try {
            var toolRegistry = registerTools(instance);

            // Register A2A if on classpath and agent has @AgentSkill methods
            if (ClasspathDetector.hasA2a()) {
                var registry = new org.atmosphere.a2a.registry.A2aRegistry();
                registry.scan(instance);

                if (!registry.skills().isEmpty()) {
                    var a2aEndpoint = annotation.endpoint().isEmpty()
                            ? basePath + "/a2a"
                            : annotation.endpoint();
                    var version = annotation.version();
                    var description = annotation.description().isEmpty()
                            ? "Headless agent: " + agentName
                            : annotation.description();

                    var card = registry.buildAgentCard(agentName, description, version, a2aEndpoint);
                    var taskManager = new org.atmosphere.a2a.runtime.TaskManager();
                    var protocolHandler = new org.atmosphere.a2a.runtime.A2aProtocolHandler(
                            registry, taskManager, card);
                    var a2aHandler = new org.atmosphere.a2a.runtime.A2aHandler(protocolHandler);

                    framework.addAtmosphereHandler(a2aEndpoint, a2aHandler, new java.util.ArrayList<>());
                    protocols.add("a2a");
                }
            }

            // Register MCP if on classpath (independent of A2A).
            // If endpoint is explicitly set and ends with /mcp, register there directly.
            // Otherwise, derive from basePath (/atmosphere/agent/{name}/mcp).
            var mcpPath = annotation.endpoint().endsWith("/mcp")
                    ? annotation.endpoint()
                    : basePath + "/mcp";
            registerMcpAt(framework, annotation, toolRegistry, instance, mcpPath, protocols);

            if (protocols.isEmpty()) {
                logger.warn("Agent '{}' is headless but no protocol modules on classpath. "
                        + "Add atmosphere-a2a or atmosphere-mcp.", agentName);
                return;
            }

            logger.info("Agent '{}' registered (headless, protocols: {})", agentName, protocols);
        } catch (Exception e) {
            logger.error("Failed to register headless agent '{}'", agentName, e);
        }
    }

    /**
     * Finds the {@code @Prompt} method or creates a synthetic one that calls
     * {@code session.stream(message)}.
     */
    private Method findPromptMethod(Class<?> clazz) {
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                return method;
            }
        }
        // No @Prompt found — use synthetic default: session.stream(message)
        try {
            return SyntheticPrompt.class.getDeclaredMethod("onPrompt",
                    String.class, org.atmosphere.ai.StreamingSession.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Failed to find synthetic prompt method", e);
        }
    }

    /**
     * Synthetic prompt handler that auto-delegates to {@code session.stream(message)}.
     * Used when the {@code @Agent} class has no {@code @Prompt} method.
     */
    static class SyntheticPrompt {
        @Prompt
        public void onPrompt(String message, org.atmosphere.ai.StreamingSession session) {
            session.stream(message);
        }
    }

    private SkillFileParser parseSkillFile(Agent annotation) {
        var skillPath = annotation.skillFile();
        if (skillPath != null && !skillPath.isEmpty()) {
            // skill: prefix loads from atmosphere-skills repo (classpath -> cache -> GitHub)
            var content = PromptLoader.resolve(skillPath);
            if (content == null) {
                logger.warn("Skill '{}' not found for agent '{}'", skillPath, annotation.name());
                return SkillFileParser.parse("");
            }
            return SkillFileParser.parse(content);
        }
        // Auto-discover skill file from classpath conventions
        var agentName = annotation.name();
        var candidates = new String[]{
                "META-INF/skills/" + agentName + "/SKILL.md",
                "prompts/" + agentName + ".md",
                "prompts/" + agentName + "-skill.md",
                "prompts/skill.md",
        };
        for (var candidate : candidates) {
            var classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = getClass().getClassLoader();
            }
            if (classLoader.getResource(candidate) != null) {
                logger.info("Auto-discovered skill file at {} for agent '{}'", candidate, agentName);
                var content = PromptLoader.load(candidate);
                return SkillFileParser.parse(content);
            }
        }
        logger.debug("No skill file found for agent '{}' — using empty system prompt", agentName);
        return SkillFileParser.parse("");
    }

    private AiConfig.LlmSettings resolveSettings() {
        var settings = AiConfig.get();
        if (settings == null) {
            settings = AiConfig.fromEnvironment();
        }
        return settings;
    }

    /**
     * Wire the v0.5 foundation primitives into the endpoint's injectables map
     * so {@code @Prompt} and {@code @AiTool} methods can declare them as
     * parameters and receive live instances. Failures are non-fatal: if the
     * workspace root can't be resolved or disk I/O fails, the agent keeps
     * starting but the affected primitive simply isn't injected (callers
     * declaring it as a parameter will see the same IllegalStateException as
     * any other missing injectable, surfacing the misconfig at the point of
     * use rather than silently).
     */
    private void buildFoundationPrimitives(String agentName,
                                           java.util.Map<Class<?>, Object> injectables) {
        var workspaceRoot = System.getProperty("atmosphere.workspace.root");
        if (workspaceRoot == null) {
            workspaceRoot = System.getenv("ATMOSPHERE_WORKSPACE_ROOT");
        }
        if (workspaceRoot == null) {
            // Default: a per-user directory under the JVM's user.home, same
            // shape as OpenClaw's convention. Tests can override with
            // -Datmosphere.workspace.root.
            workspaceRoot = System.getProperty("user.home") + "/.atmosphere/workspace";
        }
        try {
            var root = java.nio.file.Path.of(workspaceRoot).resolve("agents").resolve(agentName);
            java.nio.file.Files.createDirectories(root);
            var state = new org.atmosphere.ai.state.FileSystemAgentState(root);
            injectables.put(org.atmosphere.ai.state.AgentState.class, state);
            injectables.put(org.atmosphere.ai.state.FileSystemAgentState.class, state);
        } catch (Exception e) {
            logger.warn("AgentState not wired for '{}': {}", agentName, e.getMessage());
        }

        try {
            var credentials = org.atmosphere.ai.identity.AtmosphereEncryptedCredentialStore.withFreshKey();
            var identity = new org.atmosphere.ai.identity.InMemoryAgentIdentity(credentials);
            injectables.put(org.atmosphere.ai.identity.AgentIdentity.class, identity);
            injectables.put(org.atmosphere.ai.identity.CredentialStore.class, credentials);
        } catch (Exception e) {
            logger.warn("AgentIdentity not wired for '{}': {}", agentName, e.getMessage());
        }

        try {
            var loader = new org.atmosphere.ai.workspace.AgentWorkspaceLoader();
            var adapters = loader.adapters();
            if (!adapters.isEmpty()) {
                injectables.put(org.atmosphere.ai.workspace.AgentWorkspace.class, adapters.get(0));
            }
        } catch (Exception e) {
            logger.warn("AgentWorkspace not wired for '{}': {}", agentName, e.getMessage());
        }
    }

    private AgentRuntime resolveRuntime(AiConfig.LlmSettings settings) {
        var allBackends = AgentRuntimeResolver.resolveAll();
        for (var backend : allBackends) {
            backend.configure(settings);
        }
        return allBackends.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No AgentRuntime available. Add an AI provider "
                                + "(e.g. atmosphere-langchain4j) to the classpath."));
    }

    private AiConversationMemory resolveMemory(int maxHistory) {
        var persistence = ServiceLoader.load(ConversationPersistence.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(ConversationPersistence::isAvailable)
                .findFirst();
        if (persistence.isPresent()) {
            logger.info("Auto-detected ConversationPersistence: {}",
                    persistence.get().getClass().getName());
            return new PersistentConversationMemory(persistence.get(), maxHistory);
        }
        return new InMemoryConversationMemory(maxHistory);
    }

    private ToolRegistry registerTools(Object instance) {
        var registry = new DefaultToolRegistry();
        // Scan the agent class itself for @AiTool methods
        var hasTools = false;
        for (var method : instance.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(AiTool.class)) {
                hasTools = true;
                break;
            }
        }
        if (hasTools) {
            registry.register(instance);
        }
        return registry;
    }

    private void crossReferenceTools(SkillFileParser skillFile, ToolRegistry toolRegistry) {
        var skillTools = skillFile.listItems("Tools");
        if (skillTools.isEmpty()) {
            return;
        }
        var registeredNames = toolRegistry.allTools().stream()
                .map(t -> t.name())
                .toList();
        for (var skillTool : skillTools) {
            // Extract tool name (before any ":" or description)
            var toolName = skillTool.contains(":") ? skillTool.split(":")[0].trim() : skillTool.trim();
            if (!registeredNames.contains(toolName)) {
                logger.warn("Skill file declares tool '{}' but no matching @AiTool method found", toolName);
            }
        }
    }

    private AiMetrics resolveMetrics() {
        try {
            var metrics = ServiceLoader.load(AiMetrics.class).findFirst().orElse(AiMetrics.NOOP);
            if (metrics != AiMetrics.NOOP) {
                logger.info("Auto-detected AiMetrics: {}", metrics.getClass().getName());
            }
            return metrics;
        } catch (Exception | NoClassDefFoundError | java.util.ServiceConfigurationError e) {
            logger.debug("AiMetrics provider not available: {}", e.getMessage());
            return AiMetrics.NOOP;
        }
    }

    private void registerA2a(AtmosphereFramework framework, Agent annotation,
                             SkillFileParser skillFile, CommandRegistry commandRegistry,
                             CommandRouter commandRouter,
                             Object promptTarget, Method promptMethod,
                             org.atmosphere.ai.AiPipeline pipeline,
                             String basePath, List<String> protocols) {
        if (!ClasspathDetector.hasA2a()) {
            return;
        }
        try {
            var skills = buildSkills(skillFile, commandRegistry);
            var guardrails = skillFile.listItems("Guardrails");
            var a2aEndpoint = annotation.endpoint().isEmpty()
                    ? basePath + "/a2a" : annotation.endpoint();
            java.util.List<org.atmosphere.a2a.types.AgentExtension> extensions = null;
            if (!guardrails.isEmpty()) {
                extensions = java.util.List.of(new org.atmosphere.a2a.types.AgentExtension(
                        org.atmosphere.a2a.types.AgentExtension.GUARDRAILS_URI,
                        "Atmosphere agent guardrails", false,
                        java.util.Map.of("guardrails", guardrails)));
            }
            var capabilities = new org.atmosphere.a2a.types.AgentCapabilities(
                    true, false, extensions, true);
            var card = new org.atmosphere.a2a.types.AgentCard(
                    annotation.name(),
                    annotation.description().isEmpty() ? skillFile.title() : annotation.description(),
                    java.util.List.of(new org.atmosphere.a2a.types.AgentInterface(
                            a2aEndpoint, org.atmosphere.a2a.types.AgentInterface.JSONRPC, "1.0")),
                    null,
                    annotation.version().isEmpty() ? AGENT_VERSION : annotation.version(),
                    null,
                    capabilities,
                    null, null, null, null,
                    skills,
                    null, null);

            var registry = new org.atmosphere.a2a.registry.A2aRegistry();

            // Register executable skill handlers so A2A message/send can find them
            var bridge = new SkillBridge(commandRouter, promptTarget, promptMethod);
            bridge.setPipeline(pipeline);
            var handleCmdMethod = SkillBridge.class.getDeclaredMethod(
                    "handleCommand", org.atmosphere.a2a.runtime.TaskContext.class, String.class);
            for (var cmd : commandRegistry.allCommands()) {
                var skillId = "command" + cmd.prefix().replace("/", "_");
                var cmdBridge = new SkillBridge(bridge, cmd.prefix());
                registry.registerSkill(
                        skillId, cmd.prefix(),
                        cmd.description().isEmpty() ? "Execute " + cmd.prefix() : cmd.description(),
                        List.of("command"), handleCmdMethod, cmdBridge,
                        List.of(new org.atmosphere.a2a.registry.A2aRegistry.ParamEntry(
                                "message", "Command arguments", false, String.class)));
            }
            var handlePromptMethod = SkillBridge.class.getDeclaredMethod(
                    "handlePrompt", org.atmosphere.a2a.runtime.TaskContext.class, String.class);
            registry.registerSkill(
                    "default", "Natural Language",
                    "Process natural language messages via the agent's prompt handler",
                    List.of("nlp"), handlePromptMethod, bridge,
                    List.of(new org.atmosphere.a2a.registry.A2aRegistry.ParamEntry(
                            "message", "The message to process", true, String.class)));

            var taskManager = new org.atmosphere.a2a.runtime.TaskManager();
            var protocolHandler = new org.atmosphere.a2a.runtime.A2aProtocolHandler(
                    registry, taskManager, card);
            var a2aHandler = new org.atmosphere.a2a.runtime.A2aHandler(protocolHandler);

            var a2aPath = annotation.endpoint().isEmpty() ? basePath + "/a2a" : annotation.endpoint();
            framework.addAtmosphereHandler(a2aPath, a2aHandler, new java.util.ArrayList<>());
            protocols.add("a2a");
            logger.debug("A2A endpoint registered at {} with {} skills ({} executable)",
                    a2aPath, skills.size(), registry.skills().size());
        } catch (Exception e) {
            logger.warn("Failed to register A2A endpoint for agent: {}", e.getMessage());
        }
    }

    private List<org.atmosphere.a2a.types.AgentSkill> buildSkills(
            SkillFileParser skillFile, CommandRegistry commandRegistry) {
        var skills = new java.util.ArrayList<org.atmosphere.a2a.types.AgentSkill>();

        // Skills from skill.md
        for (var item : skillFile.listItems("Skills")) {
            skills.add(new org.atmosphere.a2a.types.AgentSkill(
                    slugify(item), item, item, List.of()));
        }

        // Commands as skills
        for (var cmd : commandRegistry.allCommands()) {
            skills.add(new org.atmosphere.a2a.types.AgentSkill(
                    "command" + cmd.prefix().replace("/", "_"),
                    cmd.prefix(),
                    cmd.description().isEmpty() ? "Execute " + cmd.prefix() : cmd.description(),
                    List.of("command")));
        }

        return List.copyOf(skills);
    }

    /**
     * Registers the agent's {@code @AiTool} methods as MCP tools via
     * programmatic registration into an MCP registry. The MCP endpoint is
     * registered at {@code basePath + "/mcp"}.
     *
     * <p>All MCP type references live in {@code McpAgentRegistration}, which
     * is loaded reflectively only when {@link ClasspathDetector#hasMcp()}
     * succeeds — so samples that depend on {@code atmosphere-agent} without
     * the optional {@code atmosphere-mcp} dependency never link any MCP
     * symbols.</p>
     */
    private void registerMcp(AtmosphereFramework framework, Agent annotation,
                              SkillFileParser skillFile, ToolRegistry toolRegistry,
                              String basePath, List<String> protocols) {
        registerMcpAt(framework, annotation, toolRegistry, null, basePath + "/mcp",
                skillFile.listItems("Guardrails"), protocols);
    }

    private void registerMcpAt(AtmosphereFramework framework, Agent annotation,
                                ToolRegistry toolRegistry, Object instance,
                                String mcpPath, List<String> protocols) {
        registerMcpAt(framework, annotation, toolRegistry, instance, mcpPath,
                List.of(), protocols);
    }

    private void registerMcpAt(AtmosphereFramework framework, Agent annotation,
                                ToolRegistry toolRegistry, Object instance,
                                String mcpPath, List<String> guardrails,
                                List<String> protocols) {
        if (!ClasspathDetector.hasMcp()) {
            logger.debug("MCP not on classpath; skipping MCP registration for agent '{}'",
                    annotation.name());
            return;
        }
        try {
            // Load the MCP bridge reflectively so AgentProcessor.class never
            // carries symbolic references to org.atmosphere.mcp.* — otherwise
            // linking this class would NoClassDefFoundError on samples that
            // omit the optional atmosphere-mcp dependency.
            var bridge = Class.forName(
                    "org.atmosphere.agent.processor.McpAgentRegistration",
                    true, Thread.currentThread().getContextClassLoader());
            var register = bridge.getDeclaredMethod("register",
                    AtmosphereFramework.class, String.class, String.class,
                    ToolRegistry.class, Object.class, String.class,
                    List.class, List.class);
            register.setAccessible(true);
            var version = annotation.version().isEmpty() ? AGENT_VERSION : annotation.version();
            register.invoke(null, framework, annotation.name(), version,
                    toolRegistry, instance, mcpPath, guardrails, protocols);
        } catch (ClassNotFoundException e) {
            logger.debug("MCP bridge class not loadable; skipping MCP registration for agent '{}'",
                    annotation.name());
        } catch (ReflectiveOperationException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Failed to register MCP endpoint for agent '{}': {}",
                    annotation.name(), cause.getMessage());
        }
    }

    /**
     * Registers an AG-UI endpoint that bridges the agent's {@code @Prompt}
     * method into the AG-UI SSE protocol. The endpoint is registered at
     * {@code basePath + "/agui"}.
     */
    private void registerAgUi(AtmosphereFramework framework,
                               Object promptTarget, Method promptMethod,
                               String basePath,
                               org.atmosphere.ai.AiPipeline pipeline,
                               List<String> protocols) {
        if (!ClasspathDetector.hasAgUi()) {
            return;
        }
        try {
            var bridge = new AgUiAgentBridge(promptTarget, promptMethod);
            var actionMethod = AgUiAgentBridge.class.getDeclaredMethod(
                    "onAction",
                    org.atmosphere.agui.runtime.RunContext.class,
                    org.atmosphere.ai.StreamingSession.class);

            var handler = new org.atmosphere.agui.runtime.AgUiHandler(
                    bridge, actionMethod, pipeline);
            framework.addAtmosphereHandler(basePath + "/agui", handler, new java.util.ArrayList<>());
            protocols.add("ag-ui");
            logger.debug("AG-UI endpoint registered at {}/agui", basePath);
        } catch (Exception e) {
            logger.warn("Failed to register AG-UI endpoint for agent: {}", e.getMessage());
        }
    }

    /**
     * Bridge between the agent's {@code @Prompt} method and the AG-UI
     * {@link org.atmosphere.agui.runtime.RunContext}-based action protocol.
     * Only loaded when atmosphere-agui is on the classpath.
     */
    static class AgUiAgentBridge {
        private final Object promptTarget;
        private final Method bridgedPromptMethod;

        AgUiAgentBridge(Object promptTarget, Method promptMethod) {
            this.promptTarget = promptTarget;
            this.bridgedPromptMethod = promptMethod;
        }

        @SuppressWarnings("unused") // invoked reflectively by AgUiHandler
        public void onAction(org.atmosphere.agui.runtime.RunContext context,
                             org.atmosphere.ai.StreamingSession session) {
            var message = context.lastUserMessage();
            if (message != null && !message.isBlank()) {
                try {
                    bridgedPromptMethod.invoke(promptTarget, message, session);
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
     * Bridge between A2A skill execution and the agent's command/prompt handling.
     * Each command skill gets its own bridge instance with the command prefix,
     * while the default NL skill delegates to the {@code @Prompt} method.
     */
    static class SkillBridge {
        private final CommandRouter commandRouter;
        private final Object promptTarget;
        private final Method bridgedPromptMethod;
        private final String commandPrefix;
        private volatile org.atmosphere.ai.AiPipeline pipeline;

        /** Primary constructor for the default (NL) bridge. */
        SkillBridge(CommandRouter commandRouter, Object promptTarget, Method promptMethod) {
            this.commandRouter = commandRouter;
            this.promptTarget = promptTarget;
            this.bridgedPromptMethod = promptMethod;
            this.commandPrefix = null;
        }

        /** Command-specific constructor that wraps a parent bridge with a fixed prefix. */
        SkillBridge(SkillBridge parent, String commandPrefix) {
            this.commandRouter = parent.commandRouter;
            this.promptTarget = parent.promptTarget;
            this.bridgedPromptMethod = parent.bridgedPromptMethod;
            this.commandPrefix = commandPrefix;
            this.pipeline = parent.pipeline;
        }

        /** Set the AI pipeline for stream() delegation. */
        void setPipeline(org.atmosphere.ai.AiPipeline pipeline) {
            this.pipeline = pipeline;
        }

        /**
         * Handles an A2A skill invocation for a command. Prepends the command prefix
         * and routes through the CommandRouter.
         */
        @SuppressWarnings("unused") // invoked reflectively by A2aProtocolHandler
        public void handleCommand(org.atmosphere.a2a.runtime.TaskContext taskCtx, String message) {
            var fullMessage = message != null && !message.isBlank()
                    ? commandPrefix + " " + message : commandPrefix;
            var result = commandRouter.route(taskCtx.taskId(), fullMessage);
            switch (result) {
                case CommandResult.Executed exec ->
                        taskCtx.complete(exec.response());
                case CommandResult.ConfirmationRequired confirm ->
                        taskCtx.complete(confirm.prompt());
                case CommandResult.NotACommand ignored ->
                        taskCtx.fail("Command not recognized: " + commandPrefix);
            }
        }

        /**
         * Handles an A2A skill invocation for natural-language messages by delegating
         * to the agent's {@code @Prompt} method.
         */
        @SuppressWarnings("unused") // invoked reflectively by A2aProtocolHandler
        public void handlePrompt(org.atmosphere.a2a.runtime.TaskContext taskCtx, String message) {
            if (message == null || message.isBlank()) {
                taskCtx.fail("Empty message");
                return;
            }

            // Fast-path: if the incoming A2A message is an @RequiresApproval
            // protocol response ("/__approval/<id>/approve"), route it through
            // the pipeline's ApprovalRegistry to unpark the waiting virtual
            // thread from a previous tool-gated invocation, rather than
            // dispatching it as a new prompt. Without this, clients using A2A
            // would see every tool-gated call time out.
            if (pipeline != null
                    && org.atmosphere.ai.approval.ApprovalRegistry.isApprovalMessage(message)
                    && pipeline.tryResolveApproval(message)) {
                taskCtx.complete("");
                return;
            }

            try {
                bridgedPromptMethod.setAccessible(true);
                // The @Prompt method signature is (String, StreamingSession). For A2A we
                // capture the output synchronously by invoking with a simple adapter
                // that collects the response text.
                var collector = new A2aStreamCollector(taskCtx, pipeline);
                bridgedPromptMethod.invoke(promptTarget, message, collector);
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
     * call sites inside this file can write {@code new A2aStreamCollector(...)}
     * without importing the fully-qualified shared base. The real concurrency,
     * finalization, and error semantics live in the shared base — previously
     * this class and {@code CoordinatorProcessor.A2aCoordinatorCollector} were
     * copy-paste clones with divergent thread-safety.
     */
    static final class A2aStreamCollector extends org.atmosphere.a2a.runtime.A2aStreamCollector {
        A2aStreamCollector(org.atmosphere.a2a.runtime.TaskContext taskCtx,
                           org.atmosphere.ai.AiPipeline pipeline) {
            super(taskCtx, pipeline);
        }
    }

    /**
     * Registers the agent's CommandRouter, system prompt, and AI pipeline with
     * ChannelAiBridge via reflection. Multiple agents can register; commands
     * route in registration order (first match wins). NL messages go through
     * the first registered agent's pipeline.
     */
    private void wireChannelBridge(String agentName, CommandRouter commandRouter,
                                    Object instance, String systemPrompt,
                                    org.atmosphere.ai.AiPipeline pipeline,
                                    List<String> channels, List<String> protocols) {
        if (!ClasspathDetector.hasChannels()) {
            return;
        }
        try {
            var bridgeClass = Class.forName("org.atmosphere.channels.ChannelAiBridge",
                    true, Thread.currentThread().getContextClassLoader());

            var register = bridgeClass.getMethod("registerAgent",
                    String.class, Object.class, Object.class,
                    String.class, Object.class, List.class);
            register.invoke(null, agentName, commandRouter, instance,
                    systemPrompt, pipeline, channels);

            protocols.add("channels");
            logger.debug("Agent '{}' registered with ChannelAiBridge for channel integration", agentName);
        } catch (ClassNotFoundException e) {
            logger.debug("ChannelAiBridge not on classpath, skipping channel integration");
        } catch (Exception e) {
            logger.warn("Failed to register agent '{}' with ChannelAiBridge: {}", agentName, e.getMessage());
        }
    }

    private String slugify(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String resolveModuleVersion() {
        try (var stream = AgentProcessor.class.getResourceAsStream(
                "/META-INF/maven/org.atmosphere/atmosphere-agent/pom.properties")) {
            if (stream != null) {
                var props = new java.util.Properties();
                props.load(stream);
                var version = props.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return "1.0.0";
    }
}
