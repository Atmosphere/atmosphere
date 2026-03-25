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
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.ConversationPersistence;
import org.atmosphere.ai.DefaultAiSupportResolver;
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
 * Annotation processor for {@link Agent}. Orchestrates the creation of an
 * agent by combining skill file parsing, command scanning, AI endpoint setup,
 * and optional cross-protocol registration.
 *
 * <h3>Orchestration steps</h3>
 * <ol>
 *   <li>Create instance, inject fields via {@link AnnotatedLifecycle}</li>
 *   <li>Parse skill.md — entire file becomes system prompt verbatim</li>
 *   <li>Scan {@code @Command} methods → {@link CommandRegistry} (auto-generates /help)</li>
 *   <li>Find {@code @Prompt} or auto-create synthetic {@code session.stream(message)}</li>
 *   <li>Resolve AI infrastructure: AiSupport, memory (on by default), tools from @AiTool</li>
 *   <li>Create {@link AgentHandler} (composition: CommandRouter + AiEndpointHandler)</li>
 *   <li>Register at {@code /atmosphere/agent/{name}}</li>
 *   <li>If atmosphere-a2a on classpath → build Agent Card, register A2A handler</li>
 *   <li>If atmosphere-mcp on classpath → bridge @AiTool methods as MCP tools, register MCP handler</li>
 *   <li>If atmosphere-agui on classpath → bridge @Prompt as AG-UI action, register AG-UI handler</li>
 *   <li>If atmosphere-channels on classpath → wire CommandRouter into ChannelAiBridge</li>
 *   <li>Log diagnostic summary</li>
 * </ol>
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
            var aiSupport = resolveAiSupport(settings);
            AiConversationMemory memory = resolveMemory(DEFAULT_MAX_HISTORY);
            var toolRegistry = registerTools(instance, framework);

            // Warn on tool mismatch with skill file
            crossReferenceTools(skillFile, toolRegistry);

            var metrics = resolveMetrics();
            var lifecycle = AnnotatedLifecycle.scan(annotatedClass);

            // Step 6: Create AgentHandler
            var aiHandler = new AiEndpointHandler(
                    promptTarget, promptMethod, 120_000L,
                    systemPrompt, path, aiSupport, List.of(),
                    memory, lifecycle, toolRegistry,
                    List.of(), List.of(), metrics, List.of(), null);

            var commandRouter = new CommandRouter(commandRegistry, instance);
            var handler = new AgentHandler(aiHandler, commandRouter);

            // Step 7: Register handler at /atmosphere/agent/{name}
            List<AtmosphereInterceptor> interceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, interceptors);
            framework.addAtmosphereHandler(path, handler, interceptors);

            // Step 8-11: Optional cross-protocol registration
            var protocols = new ArrayList<String>();
            registerA2a(framework, annotation, skillFile, commandRegistry, toolRegistry,
                    commandRouter, promptTarget, promptMethod, path, protocols);
            registerMcp(framework, annotation, toolRegistry, path, protocols);
            registerAgUi(framework, promptTarget, promptMethod, path, protocols);
            var pipeline = new org.atmosphere.ai.AiPipeline(
                    aiSupport, systemPrompt, settings.model(), memory,
                    toolRegistry, List.of(), List.of(), metrics);
            wireChannelBridge(agentName, commandRouter, instance, systemPrompt, pipeline, protocols);

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
     * Determines if an agent should run in headless mode (no WebSocket UI).
     * Headless is auto-detected when:
     * <ul>
     *   <li>{@code headless = true} is set explicitly, OR</li>
     *   <li>The class has {@code @Skill}+{@code @AgentSkillHandler} methods
     *       AND no {@code @Prompt} method</li>
     * </ul>
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
            if (ClasspathDetector.hasMcp()
                    && method.isAnnotationPresent(org.atmosphere.mcp.annotation.McpTool.class)) {
                hasProtocolMethods = true;
            }
        }
        return hasProtocolMethods && !hasPrompt;
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
            var toolRegistry = registerTools(instance, framework);

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
        if (skillPath == null || skillPath.isEmpty()) {
            return SkillFileParser.parse("");
        }
        var content = PromptLoader.load(skillPath);
        return SkillFileParser.parse(content);
    }

    private AiConfig.LlmSettings resolveSettings() {
        var settings = AiConfig.get();
        if (settings == null) {
            settings = AiConfig.fromEnvironment();
        }
        return settings;
    }

    private AiSupport resolveAiSupport(AiConfig.LlmSettings settings) {
        var allBackends = DefaultAiSupportResolver.resolveAll();
        for (var backend : allBackends) {
            backend.configure(settings);
        }
        return allBackends.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No AI support backend available. Add an atmosphere-ai-* provider "
                                + "(e.g. atmosphere-ai-openai) to the classpath."));
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

    private ToolRegistry registerTools(Object instance, AtmosphereFramework framework) {
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
                             ToolRegistry toolRegistry, CommandRouter commandRouter,
                             Object promptTarget, Method promptMethod,
                             String basePath, List<String> protocols) {
        if (!ClasspathDetector.hasA2a()) {
            return;
        }
        try {
            var skills = buildSkills(skillFile, commandRegistry);
            var card = new org.atmosphere.a2a.types.AgentCard(
                    annotation.name(),
                    annotation.description().isEmpty() ? skillFile.title() : annotation.description(),
                    annotation.endpoint().isEmpty() ? basePath + "/a2a" : annotation.endpoint(),
                    annotation.version().isEmpty() ? AGENT_VERSION : annotation.version(),
                    null, null,
                    new org.atmosphere.a2a.types.AgentCard.AgentCapabilities(true, false, false),
                    skills, null, null, null);

            var registry = new org.atmosphere.a2a.registry.A2aRegistry();

            // Register executable skill handlers so A2A message/send can find them
            var bridge = new SkillBridge(commandRouter, promptTarget, promptMethod);
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

    private List<org.atmosphere.a2a.types.Skill> buildSkills(
            SkillFileParser skillFile, CommandRegistry commandRegistry) {
        var skills = new java.util.ArrayList<org.atmosphere.a2a.types.Skill>();

        // Skills from skill.md
        for (var item : skillFile.listItems("Skills")) {
            skills.add(new org.atmosphere.a2a.types.Skill(
                    slugify(item), item, item, List.of(), null, null));
        }

        // Commands as skills
        for (var cmd : commandRegistry.allCommands()) {
            skills.add(new org.atmosphere.a2a.types.Skill(
                    "command" + cmd.prefix().replace("/", "_"),
                    cmd.prefix(),
                    cmd.description().isEmpty() ? "Execute " + cmd.prefix() : cmd.description(),
                    List.of("command"), null, null));
        }

        return List.copyOf(skills);
    }

    /**
     * Registers the agent's {@code @AiTool} methods as MCP tools via
     * programmatic registration into an {@link org.atmosphere.mcp.registry.McpRegistry}.
     * The MCP endpoint is registered at {@code basePath + "/mcp"}.
     */
    private void registerMcp(AtmosphereFramework framework, Agent annotation,
                              ToolRegistry toolRegistry, String basePath,
                              List<String> protocols) {
        registerMcpAt(framework, annotation, toolRegistry, basePath + "/mcp", protocols);
    }

    private void registerMcpAt(AtmosphereFramework framework, Agent annotation,
                                ToolRegistry toolRegistry, String mcpPath,
                                List<String> protocols) {
        registerMcpAt(framework, annotation, toolRegistry, null, mcpPath, protocols);
    }

    private void registerMcpAt(AtmosphereFramework framework, Agent annotation,
                                ToolRegistry toolRegistry, Object instance,
                                String mcpPath, List<String> protocols) {
        if (!ClasspathDetector.hasMcp()) {
            return;
        }
        try {
            var mcpRegistry = new org.atmosphere.mcp.registry.McpRegistry();

            // Bridge @AiTool methods (from ToolRegistry)
            for (var tool : toolRegistry.allTools()) {
                var params = tool.parameters().stream()
                        .map(p -> new org.atmosphere.mcp.registry.McpRegistry.ParamEntry(
                                p.name(), p.description(), p.required(),
                                jsonSchemaTypeToClass(p.type())))
                        .toList();
                mcpRegistry.registerTool(tool.name(), tool.description(), params,
                        args -> tool.executor().execute(args));
            }

            // Also scan for @McpTool, @McpResource, @McpPrompt directly on the instance
            if (instance != null) {
                mcpRegistry.scan(instance);
            }

            var protocolHandler = new org.atmosphere.mcp.runtime.McpProtocolHandler(
                    annotation.name(),
                    annotation.version().isEmpty() ? AGENT_VERSION : annotation.version(),
                    mcpRegistry,
                    framework.getAtmosphereConfig());

            var handler = new org.atmosphere.mcp.runtime.McpHandler(protocolHandler);
            framework.addAtmosphereHandler(mcpPath, handler, new java.util.ArrayList<>());
            protocols.add("mcp");
            logger.debug("MCP endpoint registered at {} with {} tools",
                    mcpPath, mcpRegistry.tools().size());
        } catch (Exception e) {
            logger.warn("Failed to register MCP endpoint for agent: {}", e.getMessage());
        }
    }

    /**
     * Registers an AG-UI endpoint that bridges the agent's {@code @Prompt}
     * method into the AG-UI SSE protocol. The endpoint is registered at
     * {@code basePath + "/agui"}.
     */
    private void registerAgUi(AtmosphereFramework framework,
                               Object promptTarget, Method promptMethod,
                               String basePath, List<String> protocols) {
        if (!ClasspathDetector.hasAgUi()) {
            return;
        }
        try {
            var bridge = new AgUiAgentBridge(promptTarget, promptMethod);
            var actionMethod = AgUiAgentBridge.class.getDeclaredMethod(
                    "onAction",
                    org.atmosphere.agui.runtime.RunContext.class,
                    org.atmosphere.ai.StreamingSession.class);

            var handler = new org.atmosphere.agui.runtime.AgUiHandler(bridge, actionMethod);
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
            try {
                bridgedPromptMethod.setAccessible(true);
                // The @Prompt method signature is (String, StreamingSession). For A2A we
                // capture the output synchronously by invoking with a simple adapter
                // that collects the response text.
                var collector = new A2aStreamCollector(taskCtx);
                bridgedPromptMethod.invoke(promptTarget, message, collector);
                collector.finalizeIfNeeded();
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getCause() != null ? e.getCause() : e;
                taskCtx.fail(cause.getMessage());
            } catch (Exception e) {
                taskCtx.fail(e.getMessage());
            }
        }
    }

    /**
     * Minimal {@link org.atmosphere.ai.StreamingSession} adapter that collects
     * streamed text and writes it as the A2A task result on completion.
     */
    static class A2aStreamCollector implements org.atmosphere.ai.StreamingSession {
        private final org.atmosphere.a2a.runtime.TaskContext taskCtx;
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean finalized;

        A2aStreamCollector(org.atmosphere.a2a.runtime.TaskContext taskCtx) {
            this.taskCtx = taskCtx;
        }

        @Override
        public String sessionId() {
            return taskCtx.taskId();
        }

        @Override
        public void send(String text) {
            buffer.append(text);
        }

        @Override
        public void stream(String message) {
            // In a full AI-wired session, stream() sends to an LLM and streams back.
            // For A2A bridging, buffer the message as the response text. Real @Prompt
            // implementations will call send() with AI-generated output instead.
            buffer.append(message);
        }

        @Override
        public void sendMetadata(String key, Object value) {
            // A2A tasks don't propagate metadata; silently ignore
        }

        @Override
        public void progress(String message) {
            taskCtx.updateStatus(org.atmosphere.a2a.types.TaskState.WORKING, message);
        }

        @Override
        public void complete() {
            if (!finalized) {
                finalized = true;
                taskCtx.complete(buffer.toString());
            }
        }

        @Override
        public void complete(String summary) {
            if (!finalized) {
                finalized = true;
                taskCtx.complete(summary != null ? summary : buffer.toString());
            }
        }

        @Override
        public void error(Throwable t) {
            if (!finalized) {
                finalized = true;
                taskCtx.fail(t.getMessage());
            }
        }

        @Override
        public boolean isClosed() {
            return finalized;
        }

        /** Ensures the task completes if the prompt method returns without calling complete(). */
        void finalizeIfNeeded() {
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

    /**
     * Registers the agent's CommandRouter, system prompt, and AI pipeline with
     * ChannelAiBridge via reflection. Multiple agents can register; commands
     * route in registration order (first match wins). NL messages go through
     * the first registered agent's pipeline.
     */
    private void wireChannelBridge(String agentName, CommandRouter commandRouter,
                                    Object instance, String systemPrompt,
                                    org.atmosphere.ai.AiPipeline pipeline,
                                    List<String> protocols) {
        if (!ClasspathDetector.hasChannels()) {
            return;
        }
        try {
            var bridgeClass = Class.forName("org.atmosphere.channels.ChannelAiBridge",
                    true, Thread.currentThread().getContextClassLoader());

            var register = bridgeClass.getMethod("registerAgent",
                    String.class, Object.class, Object.class, String.class, Object.class);
            register.invoke(null, agentName, commandRouter, instance, systemPrompt, pipeline);

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
