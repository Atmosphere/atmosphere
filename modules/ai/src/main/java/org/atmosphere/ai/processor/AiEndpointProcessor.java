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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiSupport;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.DefaultAiSupportResolver;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.ModelRouter;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
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
import java.util.Set;

/**
 * Annotation processor for {@link AiEndpoint}. Discovered by Atmosphere's annotation
 * scanning infrastructure via {@link AtmosphereAnnotation}. Scans the annotated class
 * for a {@link Prompt} method, validates the signature, and registers an
 * {@link AiEndpointHandler} at the configured path.
 *
 * <h3>Shared injection framework</h3>
 * <p>This processor delegates to the shared {@link AnnotatedLifecycle} class
 * for annotation scanning and field injection — the same infrastructure
 * used by {@link org.atmosphere.config.service.ManagedService @ManagedService}:</p>
 * <ul>
 *   <li>{@link jakarta.inject.Inject @Inject} fields are injected once at registration
 *       time via {@link AnnotatedLifecycle#injectFields}</li>
 *   <li>{@link org.atmosphere.config.service.PathParam @PathParam} fields are detected
 *       for per-request injection</li>
 *   <li>{@link org.atmosphere.config.service.Ready @Ready} and
 *       {@link org.atmosphere.config.service.Disconnect @Disconnect} lifecycle methods
 *       are discovered and delegated to the handler</li>
 * </ul>
 */
@AtmosphereAnnotation(AiEndpoint.class)
public class AiEndpointProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(AiEndpointProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(AiEndpoint.class);
            if (annotation == null) {
                return;
            }

            var promptMethod = findPromptMethod(annotatedClass);
            if (promptMethod == null) {
                logger.error("@AiEndpoint class {} has no @Prompt method", annotatedClass.getName());
                return;
            }

            validatePromptSignature(promptMethod);

            var instance = framework.newClassInstance(Object.class, annotatedClass);

            // Inject @Inject-annotated fields (Broadcaster, AtmosphereConfig, etc.)
            AnnotatedLifecycle.injectFields(framework, instance);

            var systemPrompt = resolveSystemPrompt(annotation);
            var fallbackStrategy = parseFallbackStrategy(annotation.fallbackStrategy());
            var aiSupport = resolveAiSupport();
            var interceptors = instantiateInterceptors(annotation.interceptors(), framework);
            AiConversationMemory memory = null;
            if (annotation.conversationMemory()) {
                memory = new InMemoryConversationMemory(annotation.maxHistoryMessages());
            }

            // Register tools from @AiEndpoint(tools = {...})
            var toolRegistry = registerTools(annotation, framework);

            // Instantiate guardrails and context providers
            var guardrails = instantiateGuardrails(annotation.guardrails(), framework);
            var contextProviders = instantiateContextProviders(annotation.contextProviders(), framework);

            var metrics = resolveMetrics();

            // Shared lifecycle scanning — same infrastructure as @ManagedService
            var lifecycle = AnnotatedLifecycle.scan(annotatedClass);

            var handler = new AiEndpointHandler(instance, promptMethod,
                    annotation.timeout(), systemPrompt, annotation.path(),
                    aiSupport, interceptors, memory, lifecycle,
                    toolRegistry, guardrails, contextProviders, metrics);

            List<AtmosphereInterceptor> frameworkInterceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, frameworkInterceptors);
            framework.addAtmosphereHandler(annotation.path(), handler, frameworkInterceptors);

            if (fallbackStrategy != ModelRouter.FallbackStrategy.NONE) {
                var allBackends = DefaultAiSupportResolver.resolveAll();
                if (allBackends.size() <= 1) {
                    logger.warn("fallbackStrategy={} configured but only {} AiSupport backend(s) available. "
                            + "Add more AiSupport JARs to enable routing.",
                            fallbackStrategy, allBackends.size());
                }
            }

            logger.info("AI endpoint registered at {} (class: {}, aiSupport: {}, interceptors: {}, "
                            + "memory: {}, tools: {}, guardrails: {}, contextProviders: {}, "
                            + "fallback: {}, timeout: {}ms, @Ready: {}, @Disconnect: {}, @PathParam: {})",
                    annotation.path(), annotatedClass.getSimpleName(),
                    aiSupport.name(), interceptors.size(),
                    memory != null ? "on(max=" + memory.maxMessages() + ")" : "off",
                    toolRegistry.allTools().size(),
                    guardrails.size(), contextProviders.size(),
                    fallbackStrategy,
                    annotation.timeout(),
                    lifecycle.readyMethod() != null ? lifecycle.readyMethod().getName() : "none",
                    lifecycle.disconnectMethod() != null ? lifecycle.disconnectMethod().getName() : "none",
                    lifecycle.hasPathParams() ? "yes" : "no");

        } catch (Exception e) {
            logger.error("Failed to register AI endpoint from {}", annotatedClass.getName(), e);
        }
    }

    private Method findPromptMethod(Class<?> clazz) {
        Method found = null;
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Prompt.class)) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "@AiEndpoint class " + clazz.getName()
                                    + " has multiple @Prompt methods: "
                                    + found.getName() + " and " + method.getName()
                                    + ". Exactly one @Prompt method is required.");
                }
                found = method;
            }
        }
        return found;
    }

    /**
     * Resolves the system prompt: resource file takes precedence over inline string.
     */
    private String resolveSystemPrompt(AiEndpoint annotation) {
        var resource = annotation.systemPromptResource();
        if (resource != null && !resource.isEmpty()) {
            return PromptLoader.load(resource);
        }
        return annotation.systemPrompt();
    }

    private void validatePromptSignature(Method method) {
        var params = method.getParameterTypes();
        if (params.length < 2 || params.length > 3) {
            throw new IllegalArgumentException(
                    "@Prompt method must have 2 or 3 parameters: (String, StreamingSession[, AtmosphereResource]). Found " + params.length);
        }
        if (params[0] != String.class) {
            throw new IllegalArgumentException(
                    "@Prompt method first parameter must be String. Found " + params[0].getName());
        }
        if (!org.atmosphere.ai.StreamingSession.class.isAssignableFrom(params[1])) {
            throw new IllegalArgumentException(
                    "@Prompt method second parameter must be StreamingSession. Found " + params[1].getName());
        }
        if (params.length == 3 && !org.atmosphere.cpr.AtmosphereResource.class.isAssignableFrom(params[2])) {
            throw new IllegalArgumentException(
                    "@Prompt method third parameter must be AtmosphereResource. Found " + params[2].getName());
        }
    }

    private AiSupport resolveAiSupport() {
        var support = DefaultAiSupportResolver.resolve();
        var settings = AiConfig.get();
        if (settings == null) {
            settings = AiConfig.fromEnvironment();
        }
        support.configure(settings);
        return support;
    }

    private List<AiInterceptor> instantiateInterceptors(Class<? extends AiInterceptor>[] classes,
                                                         AtmosphereFramework framework) {
        var interceptors = new ArrayList<AiInterceptor>();
        for (var clazz : classes) {
            try {
                interceptors.add(framework.newClassInstance(AiInterceptor.class, clazz));
            } catch (Exception e) {
                logger.error("Failed to instantiate AiInterceptor: {}", clazz.getName(), e);
            }
        }
        return List.copyOf(interceptors);
    }

    private ToolRegistry registerTools(AiEndpoint annotation, AtmosphereFramework framework) {
        var registry = new DefaultToolRegistry();
        var excludeSet = Set.of(annotation.excludeTools());
        for (var toolClass : annotation.tools()) {
            if (excludeSet.contains(toolClass)) {
                logger.info("Tool provider {} excluded via excludeTools", toolClass.getName());
                continue;
            }
            try {
                var toolInstance = framework.newClassInstance(Object.class, toolClass);
                registry.register(toolInstance);
            } catch (Exception e) {
                logger.error("Failed to register tool provider: {}", toolClass.getName(), e);
            }
        }
        return registry;
    }

    private List<AiGuardrail> instantiateGuardrails(Class<? extends AiGuardrail>[] classes,
                                                    AtmosphereFramework framework) {
        var guardrails = new ArrayList<AiGuardrail>();
        for (var clazz : classes) {
            try {
                guardrails.add(framework.newClassInstance(AiGuardrail.class, clazz));
            } catch (Exception e) {
                logger.error("Failed to instantiate AiGuardrail: {}", clazz.getName(), e);
            }
        }
        return List.copyOf(guardrails);
    }

    private List<ContextProvider> instantiateContextProviders(Class<? extends ContextProvider>[] classes,
                                                              AtmosphereFramework framework) {
        var providers = new ArrayList<ContextProvider>();
        for (var clazz : classes) {
            try {
                providers.add(framework.newClassInstance(ContextProvider.class, clazz));
            } catch (Exception e) {
                logger.error("Failed to instantiate ContextProvider: {}", clazz.getName(), e);
            }
        }
        return List.copyOf(providers);
    }

    private AiMetrics resolveMetrics() {
        return ServiceLoader.load(AiMetrics.class).findFirst().orElse(AiMetrics.NOOP);
    }

    private ModelRouter.FallbackStrategy parseFallbackStrategy(String value) {
        try {
            return ModelRouter.FallbackStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown fallbackStrategy '{}', defaulting to NONE", value);
            return ModelRouter.FallbackStrategy.NONE;
        }
    }
}
