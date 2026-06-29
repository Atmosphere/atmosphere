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
import org.atmosphere.ai.ModelTier;
import org.atmosphere.ai.PersistentConversationMemory;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.RoutingAiSupport;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.governance.GovernancePolicies;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyAsGuardrail;
import org.atmosphere.ai.governance.memory.MemorySafetyConfig;
import org.atmosphere.ai.memory.LongTermMemoryInterceptor;
import org.atmosphere.ai.governance.rag.RagSafetyConfig;
import org.atmosphere.ai.governance.scope.ScopePolicyBuilder;
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

            // Resolve + install the long-term-memory injection-safety policy (OWASP
            // Agentic A03) once per framework, before any LongTermMemoryInterceptor
            // is constructed below, so operator overrides (atmosphere.ai.memory.safety.*
            // bridged into init-params by Spring / Quarkus) take effect and the
            // runtime-state is published for the console (Invariant #5). Framework-
            // agnostic — the same call serves the @AiEndpoint path on every runtime.
            installMemorySafetyOnce(framework);

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
            // Publish memory injection-safety as runtime truth only when this
            // endpoint actually wires long-term memory — symmetric to ragSafety,
            // which publishes only once a ContextProvider is wrapped. Advertising
            // the screen on an endpoint with no memory store would be exactly the
            // configured-intent overstatement Invariant #5 forbids.
            if (interceptors.stream().anyMatch(i -> i instanceof LongTermMemoryInterceptor)) {
                MemorySafetyConfig.installedDefault().publishActive(framework);
            }
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
                var scopePolicy = ScopePolicyBuilder.build(
                        scopeAnnotation, annotatedClass, annotation.path()).orElse(null);
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
                    annotation.contextProviders(), annotation.autoDiscoverContextProviders(),
                    framework, annotation.path());

            var metrics = resolveMetrics();
            var broadcastFilters = instantiateBroadcastFilters(annotation.filters(), framework);

            // Validate required capabilities
            validateCapabilities(annotation.requires(), runtime, annotation.path());

            // Per-endpoint model override. The declared value may optionally be a
            // provider-neutral tier alias ("fast"/"frontier"/"reasoning");
            // ModelTier.resolve maps it to a concrete model for the active
            // provider (detected from the runtime-resolved base URL). Any other
            // value — including raw model ids and the empty string — passes
            // through unchanged, so existing endpoints are unaffected.
            var resolvedModel = ModelTier.resolve(
                    annotation.model(),
                    settings != null ? settings.baseUrl() : null,
                    settings != null ? settings.model() : null);
            var endpointModel = (resolvedModel == null || resolvedModel.isEmpty()) ? null : resolvedModel;

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
            // Endpoint-scoped self-healing structured-output reprompt budget.
            if (annotation.structuredOutputRetries() > 0) {
                handler.setStructuredOutputRetries(annotation.structuredOutputRetries());
            }
            // Endpoint-scoped dynamic tool-catalog cap.
            if (annotation.maxToolsPerRequest() > 0) {
                handler.setMaxToolsPerRequest(annotation.maxToolsPerRequest());
            }

            // Per-endpoint stream cache: set the framework's cache class name
            // before the broadcaster is created so the new broadcaster picks up
            // the requested cache (matches @ManagedService precedent). Skipped
            // when the annotation keeps the default DefaultBroadcasterCache.
            applyStreamCache(annotation.streamCache(), annotation.path(), framework);

            List<AtmosphereInterceptor> frameworkInterceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(framework, frameworkInterceptors);
            // Per-endpoint heartbeat override: if @AiEndpoint.heartbeatSeconds is
            // non-zero, reconfigure the HeartbeatInterceptor instance attached to
            // *this* endpoint without touching the framework default. Closes the
            // long-tool-approval gap where CloudFront / Cloudflare / NGINX close
            // idle streams after ~60 s while a parked virtual thread is waiting
            // out a @RequiresApproval that takes minutes to resolve.
            applyHeartbeatOverride(annotation.heartbeatSeconds(), annotation.path(),
                    framework, frameworkInterceptors);
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
        // Offer the code-as-action tool only when code execution is enabled and a
        // container engine is confirmed present (Correctness Invariant #5). The
        // per-session sandbox is installed at dispatch time by AiEndpointHandler.
        var codeExec = org.atmosphere.ai.code.CodeExecSupport.shared();
        if (codeExec.isEnabled()) {
            registry.register(codeExec.tool());
            logger.info("Code-as-action enabled: registered '{}' tool",
                    org.atmosphere.ai.code.CodeExecTool.TOOL_NAME);
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
     * Merge declarative governance policies from ServiceLoader and the
     * framework-scoped {@link GovernancePolicy#POLICIES_PROPERTY} bag.
     * Delegates to {@link GovernancePolicies#installed(AtmosphereFramework)} so
     * the {@code @AiEndpoint} path and the {@code @Agent} / {@code @Coordinator}
     * pipelines resolve the same installed policy set (Mode Parity).
     */
    private List<GovernancePolicy> instantiatePolicies(AtmosphereFramework framework) {
        return GovernancePolicies.installed(framework);
    }

    /**
     * Resolve and install the framework-wide long-term-memory injection-safety
     * policy exactly once per framework. Guarded by a one-shot marker in the
     * property bag so the install + runtime-state publish (and its single log
     * line) happen on the first {@code @AiEndpoint} processed, not per-endpoint.
     * The memory screen is on out of the box regardless (the
     * {@link org.atmosphere.ai.governance.memory.MemorySafetyConfig#installedDefault()}
     * holder begins fail-closed-on); this call applies any operator override and
     * makes the active state visible to the console.
     */
    private void installMemorySafetyOnce(AtmosphereFramework framework) {
        var cfg = framework.getAtmosphereConfig();
        if (cfg == null) {
            return;
        }
        if (cfg.properties().putIfAbsent("org.atmosphere.ai.memory.safety.bridged", Boolean.TRUE) == null) {
            MemorySafetyConfig.installedFrom(framework);
        }
    }

    private List<ContextProvider> instantiateContextProviders(Class<? extends ContextProvider>[] classes,
                                                              boolean autoDiscover,
                                                              AtmosphereFramework framework,
                                                              String endpointPath) {
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

        // Screen every retrieved document for indirect prompt injection (OWASP
        // Agentic A04) before it reaches the LLM. Default-on, fail-closed,
        // rule-based; operators relax via atmosphere.ai.rag.safety.* — see
        // RagSafetyConfig. No-op when no provider is declared (no RAG surface).
        return RagSafetyConfig.from(framework.getAtmosphereConfig())
                .apply(List.copyOf(providers), framework, endpointPath);
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

    /**
     * Apply the per-endpoint {@link org.atmosphere.cpr.BroadcasterCache} class
     * so streaming AI responses are buffered and replayed to a reconnecting
     * client whose UUID matches the original session. Sets the framework-wide
     * cache class name (mirroring {@code @ManagedService} precedent) before
     * the broadcaster is created so the new broadcaster picks up the
     * requested cache.
     *
     * <p>No-op when the annotation keeps the default
     * {@link org.atmosphere.cache.DefaultBroadcasterCache} so apps that
     * declare {@code @AiEndpoint} without a {@code streamCache} field never
     * mutate framework state.</p>
     */
    private void applyStreamCache(Class<? extends org.atmosphere.cpr.BroadcasterCache> cacheClass,
                                  String path, AtmosphereFramework framework) {
        if (cacheClass == null
                || cacheClass.equals(org.atmosphere.cache.DefaultBroadcasterCache.class)) {
            return;
        }
        framework.setBroadcasterCacheClassName(cacheClass.getName());
        logger.info("AI endpoint {} attached BroadcasterCache: {}",
                path, cacheClass.getName());
    }

    /**
     * Attach a per-endpoint {@link org.atmosphere.interceptor.HeartbeatInterceptor}
     * configured at the requested frequency. The default ManagedService chain
     * does not include the heartbeat interceptor, so we add it explicitly here
     * when the annotation requests one.
     *
     * @param heartbeatSeconds the {@code @AiEndpoint.heartbeatSeconds} value:
     *                         {@code 0} = no per-endpoint heartbeat (default),
     *                         positive = attach interceptor at that frequency,
     *                         negative = explicit no-op (cosmetic, future-proofing)
     * @param path             the endpoint path (logging only)
     * @param framework        the framework, used to instantiate the interceptor
     * @param interceptors     the interceptor list to append to
     */
    private void applyHeartbeatOverride(int heartbeatSeconds, String path,
                                        AtmosphereFramework framework,
                                        List<AtmosphereInterceptor> interceptors) {
        if (heartbeatSeconds == 0) {
            return; // no per-endpoint heartbeat
        }
        if (heartbeatSeconds < 0) {
            logger.info("AI endpoint {} heartbeatSeconds=-1 (no per-endpoint heartbeat)", path);
            return;
        }
        try {
            var instance = framework.newClassInstance(AtmosphereInterceptor.class,
                    org.atmosphere.interceptor.HeartbeatInterceptor.class);
            if (instance instanceof org.atmosphere.interceptor.HeartbeatInterceptor hb) {
                hb.heartbeatFrequencyInSeconds(heartbeatSeconds);
                interceptors.add(hb);
                logger.info("AI endpoint {} attached HeartbeatInterceptor at {} seconds",
                        path, heartbeatSeconds);
            } else {
                logger.warn("AI endpoint {} could not attach HeartbeatInterceptor: "
                                + "framework returned {} instead",
                        path, instance == null ? "null" : instance.getClass().getName());
            }
        } catch (Throwable t) {
            // Best-effort: if the interceptor can't be instantiated (e.g. CDI
            // issues), log loudly and continue — the endpoint still works,
            // it just won't have a per-endpoint heartbeat.
            logger.warn("AI endpoint {} failed to attach HeartbeatInterceptor at {} seconds",
                    path, heartbeatSeconds, t);
        }
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
