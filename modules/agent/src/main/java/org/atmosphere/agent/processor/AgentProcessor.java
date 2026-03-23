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
 *   <li>If atmosphere-mcp on classpath → expose tools + commands as MCP tools</li>
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

            // Step 8-9: Optional cross-protocol registration
            var protocols = new ArrayList<String>();
            registerA2a(framework, annotation, skillFile, commandRegistry, toolRegistry, path, protocols);

            // Step 10: Log summary
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
                             ToolRegistry toolRegistry, String basePath,
                             List<String> protocols) {
        if (!ClasspathDetector.hasA2a()) {
            return;
        }
        try {
            var skills = buildA2aSkills(skillFile, commandRegistry);
            var card = new org.atmosphere.a2a.types.AgentCard(
                    annotation.name(),
                    annotation.description().isEmpty() ? skillFile.title() : annotation.description(),
                    basePath + "/a2a",
                    AGENT_VERSION,
                    null, null,
                    new org.atmosphere.a2a.types.AgentCard.AgentCapabilities(true, false, false),
                    skills, null, null, null);

            var registry = new org.atmosphere.a2a.registry.A2aRegistry();
            var taskManager = new org.atmosphere.a2a.runtime.TaskManager();
            var protocolHandler = new org.atmosphere.a2a.runtime.A2aProtocolHandler(
                    registry, taskManager, card);
            var a2aHandler = new org.atmosphere.a2a.runtime.A2aHandler(protocolHandler);

            framework.addAtmosphereHandler(basePath + "/a2a", a2aHandler, new java.util.ArrayList<>());
            protocols.add("a2a");
            logger.debug("A2A endpoint registered at {}/a2a with {} skills",
                    basePath, skills.size());
        } catch (Exception e) {
            logger.warn("Failed to register A2A endpoint for agent: {}", e.getMessage());
        }
    }

    private List<org.atmosphere.a2a.types.Skill> buildA2aSkills(
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

    private void registerMcp(AtmosphereFramework framework, Agent annotation,
                             CommandRegistry commandRegistry, ToolRegistry toolRegistry,
                             String basePath, List<String> protocols) {
        if (!ClasspathDetector.hasMcp()) {
            return;
        }
        try {
            var mcpRegistry = new org.atmosphere.mcp.registry.McpRegistry();

            var protocolHandler = new org.atmosphere.mcp.runtime.McpProtocolHandler(
                    annotation.name(), AGENT_VERSION, mcpRegistry, framework.getAtmosphereConfig());
            var mcpHandler = new org.atmosphere.mcp.runtime.McpHandler(protocolHandler);

            framework.addAtmosphereHandler(basePath + "/mcp", mcpHandler, new java.util.ArrayList<>());
            protocols.add("mcp");
            logger.debug("MCP endpoint registered at {}/mcp", basePath);
        } catch (Exception e) {
            logger.warn("Failed to register MCP endpoint for agent: {}", e.getMessage());
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
