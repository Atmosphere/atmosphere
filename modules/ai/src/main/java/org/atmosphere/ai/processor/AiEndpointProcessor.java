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

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.ConversationPersistence;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.DefaultModelRouter;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.ModelRouter;
import org.atmosphere.ai.PersistentConversationMemory;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.RoutingAiSupport;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopeGuardrailResolver;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.annotation.AnnotationUtil;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.managed.AnnotatedLifecycle;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.BroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation processor for {@link AiEndpoint}. Scans the annotated class for a
 * {@link Prompt} method, validates the signature, and registers an
 * {@link AiEndpointHandler} at the configured path. Field injection and lifecycle
 * discovery are handled by {@link AnnotatedLifecycle}.
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
            var settings = resolveSettings();
            var runtime = resolveRuntimeWithRouting(fallbackStrategy, settings,
                    annotation.requires());
            var interceptors = instantiateInterceptors(annotation.interceptors(), framework);
            AiConversationMemory memory = null;
            if (annotation.conversationMemory()) {
                memory = resolveMemory(annotation.maxHistoryMessages());
            }

            // Register tools from @AiEndpoint(tools = {...})
            var toolRegistry = registerTools(annotation, framework);

            // Instantiate guardrails and context providers
            var guardrails = instantiateGuardrails(annotation.guardrails(), framework);
            // Declarative governance policies are merged into the guardrail
            // list through PolicyAsGuardrail so the handler's existing wiring
            // keeps working while the GovernancePolicy SPI picks up Spring /
            // Quarkus / ServiceLoader sources through POLICIES_PROPERTY.
            var policies = instantiatePolicies(framework);
            // @AgentScope on the endpoint class becomes an auto-installed
            // ScopePolicy. Sample-hygiene CI lint (separate commit) rejects
            // samples that neither declare @AgentScope nor opt out via
            // unrestricted = true.
            var scopeAnnotation = annotatedClass.getAnnotation(AgentScope.class);
            if (scopeAnnotation != null) {
                var scopePolicy = buildScopePolicy(scopeAnnotation, annotatedClass, annotation.path());
                if (scopePolicy != null) {
                    var extended = new ArrayList<GovernancePolicy>(policies.size() + 1);
                    extended.add(scopePolicy);      // scope runs first — cheapest rejection
                    extended.addAll(policies);
                    policies = List.copyOf(extended);
                }
            }
            if (!policies.isEmpty()) {
                var merged = new ArrayList<AiGuardrail>(guardrails.size() + policies.size());
                merged.addAll(guardrails);
                for (var policy : policies) {
                    merged.add(new PolicyAsGuardrail(policy));
                }
                guardrails = List.copyOf(merged);
            }
            var contextProviders = instantiateContextProviders(
                    annotation.contextProviders(), annotation.autoDiscoverContextProviders(), framework);

            var metrics = resolveMetrics();
            var broadcastFilters = instantiateBroadcastFilters(annotation.filters(), framework);

            // Validate required capabilities
            validateCapabilities(annotation.requires(), runtime, annotation.path());

            // Per-endpoint model override
            var endpointModel = annotation.model().isEmpty() ? null : annotation.model();

            // Shared lifecycle scanning — same infrastructure as @ManagedService
            var lifecycle = AnnotatedLifecycle.scan(annotatedClass);

            var responseType = annotation.responseAs() == Void.class
                    ? null : annotation.responseAs();
            var injectables = responseType != null
                    ? java.util.Map.<Class<?>, Object>of(Class.class, responseType)
                    : java.util.Map.<Class<?>, Object>of();
            var handler = new AiEndpointHandler(instance, promptMethod,
                    annotation.timeout(), systemPrompt, annotation.path(),
                    runtime, interceptors, memory, lifecycle,
                    toolRegistry, guardrails, contextProviders, metrics,
                    broadcastFilters, endpointModel, injectables);

            // Endpoint-scoped prompt cache policy.
            var cachePolicy = annotation.promptCache();
            if (cachePolicy != org.atmosphere.ai.llm.CacheHint.CachePolicy.NONE) {
                handler.setCachePolicy(cachePolicy);
            }
            // Endpoint-scoped retry policy. maxRetries = -1 sentinel means
            // "inherit client default" — do not thread anything.
            var retry = annotation.retry();
            if (retry.maxRetries() >= 0) {
                handler.setRetryPolicy(new org.atmosphere.ai.RetryPolicy(
                        retry.maxRetries(),
                        java.time.Duration.ofMillis(retry.initialDelayMs()),
                        java.time.Duration.ofMillis(retry.maxDelayMs()),
                        retry.backoffMultiplier(),
                        org.atmosphere.ai.RetryPolicy.DEFAULT.retryableErrors()));
            }

            List<AtmosphereInterceptor> frameworkInterceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, frameworkInterceptors);
            framework.addAtmosphereHandler(annotation.path(), handler, frameworkInterceptors);

            logger.info("AI endpoint registered at {} (class: {}, runtime: {}, interceptors: {}, "
                            + "memory: {}, tools: {}, guardrails: {}, contextProviders: {}, "
                            + "filters: {}, fallback: {}, timeout: {}ms, "
                            + "@Ready: {}, @Disconnect: {}, @PathParam: {})",
                    annotation.path(), annotatedClass.getSimpleName(),
                    runtime.name(), interceptors.size(),
                    memory != null ? "on(max=" + memory.maxMessages() + ")" : "off",
                    toolRegistry.allTools().size(),
                    guardrails.size(), contextProviders.size(),
                    broadcastFilters.size(), fallbackStrategy,
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
            return PromptLoader.resolve(resource);
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

    private AiConfig.LlmSettings resolveSettings() {
        var settings = AiConfig.get();
        if (settings == null) {
            settings = AiConfig.fromEnvironment();
        }
        return settings;
    }

    private AgentRuntime resolveRuntimeWithRouting(ModelRouter.FallbackStrategy strategy,
                                                  AiConfig.LlmSettings settings,
                                                  AiCapability[] requiredCapabilities) {
        var allBackends = AgentRuntimeResolver.resolveAll();
        for (var backend : allBackends) {
            // configure() may throw if a runtime tries to eagerly construct its
            // native client and the underlying CDI / connection pool / TLS layer
            // is not yet ready (Quarkus L4j synthetic ChatModel beans pull in
            // TlsConfigurationRegistry which is not initialised during servlet
            // init). Swallow the exception with a clear log line so endpoint
            // registration completes; the runtime will be re-resolved at request
            // time when the bean graph is fully wired and report isAvailable=false
            // through the normal capability check if it is still misconfigured.
            try {
                backend.configure(settings);
            } catch (RuntimeException e) {
                logger.warn("Backend {} failed eager configure() — endpoint registration "
                                + "will continue and the runtime will be re-resolved at request time. "
                                + "Reason: {}", backend.name(), e.toString());
            }
        }

        if (strategy != ModelRouter.FallbackStrategy.NONE && allBackends.size() > 1) {
            var router = new DefaultModelRouter(strategy);
            var required = Set.of(requiredCapabilities);
            logger.info("Routing enabled: strategy={}, backends={}", strategy,
                    allBackends.stream().map(AgentRuntime::name).toList());
            return new RoutingAiSupport(router, allBackends, required);
        }

        if (strategy != ModelRouter.FallbackStrategy.NONE && allBackends.size() <= 1) {
            logger.warn("fallbackStrategy={} configured but only {} AgentRuntime backend(s) available. "
                    + "Add more AgentRuntime JARs to enable routing.",
                    strategy, allBackends.size());
        }
        return allBackends.getFirst();
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
        // Merge three sources so a user-defined @AiEndpoint gets the same
        // guardrail wiring as the default endpoint:
        //   1. annotation-declared guardrails (highest precedence)
        //   2. ServiceLoader-discovered guardrails (SPI wiring for plain
        //      servlet / Quarkus / non-Spring deployments)
        //   3. framework-property bridged guardrails (Spring auto-config
        //      writes its bean list here — see AiGuardrail.GUARDRAILS_PROPERTY)
        // Duplicates by concrete class are de-duped; the annotation-declared
        // instance wins on conflict.
        var merged = new java.util.LinkedHashMap<Class<?>, AiGuardrail>();
        for (var clazz : classes) {
            try {
                var instance = framework.newClassInstance(AiGuardrail.class, clazz);
                merged.putIfAbsent(clazz, instance);
            } catch (Exception e) {
                logger.error("Failed to instantiate AiGuardrail: {}", clazz.getName(), e);
            }
        }
        try {
            for (var g : java.util.ServiceLoader.load(AiGuardrail.class)) {
                if (g != null) {
                    merged.putIfAbsent(g.getClass(), g);
                }
            }
        } catch (java.util.ServiceConfigurationError e) {
            logger.warn("AiGuardrail ServiceLoader lookup failed: {}", e.getMessage());
        }
        var cfg = framework.getAtmosphereConfig();
        if (cfg != null) {
            var bridged = cfg.properties().get(AiGuardrail.GUARDRAILS_PROPERTY);
            if (bridged instanceof List<?> list) {
                for (var g : list) {
                    if (g instanceof AiGuardrail ai) {
                        merged.putIfAbsent(ai.getClass(), ai);
                    }
                }
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * Build a {@link ScopePolicy} from the {@link AgentScope} annotation on
     * an endpoint class. Returns {@code null} when the annotation is invalid
     * (e.g. {@code unrestricted = false} with blank purpose) and logs the
     * reason — the endpoint keeps working without scope enforcement, which
     * is the "refuse to break at startup" behaviour callers expect; the
     * sample-hygiene CI lint catches this class of misconfiguration before
     * it ships.
     */
    private static ScopePolicy buildScopePolicy(AgentScope annotation,
                                                 Class<?> endpointClass,
                                                 String endpointPath) {
        try {
            var config = ScopeConfig.fromAnnotation(annotation);
            var guardrail = ScopeGuardrailResolver.resolve(config.tier());
            if (guardrail.tier() != config.tier()) {
                logger.warn("No {} ScopeGuardrail impl on the classpath for endpoint {} — "
                                + "falling back to rule-based tier; install atmosphere-ai-scope-<tier> for the "
                                + "intended behaviour", config.tier(), endpointPath);
            }
            var name = "scope::" + endpointClass.getSimpleName();
            var source = "annotation:" + endpointClass.getName();
            return new ScopePolicy(name, source, "1.0",
                    // rebuild config against the guardrail we actually resolved
                    // so we don't advertise EMBEDDING_SIMILARITY when only the
                    // RULE_BASED fallback is installed
                    new ScopeConfig(config.purpose(), config.forbiddenTopics(),
                            config.onBreach(), config.redirectMessage(),
                            guardrail.tier(), config.similarityThreshold(),
                            config.postResponseCheck(), config.unrestricted(),
                            config.justification()),
                    guardrail);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid @AgentScope on {} ({}) — endpoint will run WITHOUT scope "
                    + "enforcement; fix the annotation or add unrestricted = true with a justification",
                    endpointClass.getName(), endpointPath, e);
            return null;
        }
    }

    /**
     * Merge declarative governance policies from ServiceLoader and the
     * framework-scoped {@link GovernancePolicy#POLICIES_PROPERTY} bag.
     * Deduplicates by {@link GovernancePolicy#name()} so repeat wiring
     * (Spring + ServiceLoader + YAML pre-loaded into the property) cannot
     * double-install the same policy.
     */
    private List<GovernancePolicy> instantiatePolicies(AtmosphereFramework framework) {
        var merged = new java.util.LinkedHashMap<String, GovernancePolicy>();
        try {
            for (var p : ServiceLoader.load(GovernancePolicy.class)) {
                if (p != null) {
                    merged.putIfAbsent(p.name(), p);
                }
            }
        } catch (java.util.ServiceConfigurationError e) {
            logger.warn("GovernancePolicy ServiceLoader lookup failed: {}", e.getMessage());
        }
        var cfg = framework.getAtmosphereConfig();
        if (cfg != null) {
            var bridged = cfg.properties().get(GovernancePolicy.POLICIES_PROPERTY);
            if (bridged instanceof List<?> list) {
                for (var p : list) {
                    if (p instanceof GovernancePolicy policy) {
                        merged.putIfAbsent(policy.name(), policy);
                    }
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private List<ContextProvider> instantiateContextProviders(Class<? extends ContextProvider>[] classes,
                                                              boolean autoDiscover,
                                                              AtmosphereFramework framework) {
        var providers = new ArrayList<ContextProvider>();
        var declaredTypes = new HashSet<String>();
        for (var clazz : classes) {
            try {
                providers.add(framework.newClassInstance(ContextProvider.class, clazz));
                declaredTypes.add(clazz.getName());
            } catch (Exception e) {
                logger.error("Failed to instantiate ContextProvider: {}", clazz.getName(), e);
            }
        }

        // Auto-discover additional ContextProviders via ServiceLoader only when opted in
        if (autoDiscover) {
            try {
                for (var discovered : ServiceLoader.load(ContextProvider.class)) {
                    if (!declaredTypes.contains(discovered.getClass().getName()) && discovered.isAvailable()) {
                        providers.add(discovered);
                        logger.info("Auto-discovered ContextProvider: {}", discovered.getClass().getName());
                    }
                }
            } catch (Exception | java.util.ServiceConfigurationError e) {
                logger.debug("ContextProvider auto-discovery not available: {}", e.getMessage());
            }
        }

        return List.copyOf(providers);
    }

    private List<BroadcastFilter> instantiateBroadcastFilters(
            Class<? extends BroadcastFilter>[] classes, AtmosphereFramework framework) {
        var filters = new ArrayList<BroadcastFilter>();
        for (var clazz : classes) {
            try {
                filters.add(framework.newClassInstance(BroadcastFilter.class, clazz));
            } catch (Exception e) {
                logger.error("Failed to instantiate BroadcastFilter: {}", clazz.getName(), e);
            }
        }
        return List.copyOf(filters);
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

    private AiMetrics resolveMetrics() {
        try {
            var metrics = ServiceLoader.load(AiMetrics.class).findFirst().orElse(AiMetrics.NOOP);
            if (metrics != AiMetrics.NOOP) {
                logger.info("Auto-detected AiMetrics: {}", metrics.getClass().getName());
            }
            return metrics;
        } catch (Exception | NoClassDefFoundError | java.util.ServiceConfigurationError e) {
            // MicrometerAiMetrics is listed in META-INF/services but micrometer-core
            // is not on the classpath, or the provider declaration is broken — fall back to NOOP
            logger.debug("AiMetrics provider not available: {}", e.getMessage());
            return AiMetrics.NOOP;
        }
    }

    private ModelRouter.FallbackStrategy parseFallbackStrategy(String value) {
        try {
            return ModelRouter.FallbackStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown fallbackStrategy '{}', defaulting to NONE", value);
            return ModelRouter.FallbackStrategy.NONE;
        }
    }

    private void validateCapabilities(AiCapability[] required, AgentRuntime runtime, String path) {
        if (required.length == 0) {
            return;
        }
        var supported = runtime.capabilities();
        var missing = Arrays.stream(required)
                .filter(cap -> !supported.contains(cap))
                .collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "@AiEndpoint at " + path + " requires capabilities " + missing
                            + " but backend '" + runtime.name() + "' only provides " + supported
                            + ". Use a different backend or remove the requires declaration.");
        }
        logger.info("Capability check passed for {}: required={}, backend={}",
                path, Arrays.toString(required), runtime.name());
    }
}
