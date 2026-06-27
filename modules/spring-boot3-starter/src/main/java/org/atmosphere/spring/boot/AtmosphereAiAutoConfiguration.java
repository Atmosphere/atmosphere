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
package org.atmosphere.spring.boot;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.facts.FactResolver;
import org.atmosphere.ai.filter.PiiRedactionFilter;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.memory.MemorySafetyConfig;
import org.atmosphere.ai.governance.rag.RagSafetyConfig;
import org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail;
import org.atmosphere.ai.guardrails.PiiRedactionGuardrail;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Atmosphere AI module.
 * Activates when {@code atmosphere-ai} is on the classpath and
 * {@code atmosphere.ai.enabled=true} (default).
 *
 * <p>Configures the LLM settings from Spring properties (with environment variable
 * fallback) and registers a default AI chat endpoint when no user-defined
 * {@code @AiEndpoint} is present.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(AiConfig.class)
@ConditionalOnBean(AtmosphereFramework.class)
@ConditionalOnProperty(name = "atmosphere.ai.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereAiAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(AiConfig.LlmSettings.class)
    public AiConfig.LlmSettings atmosphereAiSettings(AtmosphereProperties properties) {
        var aiProps = properties.getAi();
        var apiKey = resolveApiKey(aiProps);
        if (apiKey == null && !"local".equalsIgnoreCase(aiProps.getMode())) {
            logger.warn("No AI API key configured. Set atmosphere.ai.api-key, "
                    + "LLM_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY environment variable");
        }
        var settings = AiConfig.configure(
                aiProps.getMode(),
                aiProps.getModel(),
                apiKey,
                aiProps.getBaseUrl());
        logger.info("Atmosphere AI configured: mode={}, model={}", aiProps.getMode(), aiProps.getModel());
        return settings;
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereAiEndpointRegistrar atmosphereAiEndpointRegistrar(
            AtmosphereFramework framework,
            AtmosphereProperties properties,
            org.springframework.beans.factory.ObjectProvider<AiGuardrail> guardrailProvider,
            org.springframework.beans.factory.ObjectProvider<GovernancePolicy> policyProvider) {
        var guardrails = guardrailProvider.orderedStream().toList();
        // Bridge the bean list into framework properties so user-defined
        // @AiEndpoint classes (which go through AiEndpointProcessor, not this
        // registrar) also pick them up.
        if (!guardrails.isEmpty()) {
            framework.getAtmosphereConfig().properties()
                    .put(AiGuardrail.GUARDRAILS_PROPERTY, guardrails);
            logger.info("Bridged {} Spring AiGuardrail bean(s) into framework properties: {}",
                    guardrails.size(),
                    guardrails.stream().map(g -> g.getClass().getSimpleName()).toList());
        }
        var policies = policyProvider.orderedStream().toList();
        if (!policies.isEmpty()) {
            framework.getAtmosphereConfig().properties()
                    .put(GovernancePolicy.POLICIES_PROPERTY, policies);
            logger.info("Bridged {} Spring GovernancePolicy bean(s) into framework properties: {}",
                    policies.size(),
                    policies.stream().map(GovernancePolicy::name).toList());
        }
        // Bridge the RAG injection-safety policy into framework init-params so
        // AiEndpointProcessor wraps every @AiEndpoint ContextProvider. On by
        // default and fail-closed; disable with atmosphere.ai.rag.safety.enabled=false.
        var rag = properties.getAi().getRag().getSafety();
        framework.addInitParameter(RagSafetyConfig.ENABLED_KEY, String.valueOf(rag.isEnabled()));
        framework.addInitParameter(RagSafetyConfig.FAIL_OPEN_KEY, String.valueOf(rag.isFailOpen()));
        if (rag.getTier() != null) {
            framework.addInitParameter(RagSafetyConfig.TIER_KEY, rag.getTier());
        }
        if (rag.getOnBreach() != null) {
            framework.addInitParameter(RagSafetyConfig.ON_BREACH_KEY, rag.getOnBreach());
        }
        logger.info("RAG injection-safety: enabled={}, tier={}, onBreach={}, failOpen={}",
                rag.isEnabled(), rag.getTier(), rag.getOnBreach(), rag.isFailOpen());
        // Bridge the long-term-memory injection-safety policy (OWASP Agentic A03)
        // into framework init-params, then resolve + install it as the framework
        // default so LongTermMemoryInterceptor screens every extracted fact before
        // it is persisted. On by default and fail-closed; disable with
        // atmosphere.ai.memory.safety.enabled=false.
        var mem = properties.getAi().getMemory().getSafety();
        framework.addInitParameter(MemorySafetyConfig.ENABLED_KEY, String.valueOf(mem.isEnabled()));
        framework.addInitParameter(MemorySafetyConfig.FAIL_OPEN_KEY, String.valueOf(mem.isFailOpen()));
        if (mem.getTier() != null) {
            framework.addInitParameter(MemorySafetyConfig.TIER_KEY, mem.getTier());
        }
        if (mem.getOnBreach() != null) {
            framework.addInitParameter(MemorySafetyConfig.ON_BREACH_KEY, mem.getOnBreach());
        }
        // AiEndpointProcessor resolves + installs + publishes this once per
        // framework (before any LongTermMemoryInterceptor is built), so the
        // bridge only needs to seed the init-params above.
        logger.info("Memory injection-safety: enabled={}, tier={}, onBreach={}, failOpen={}",
                mem.isEnabled(), mem.getTier(), mem.getOnBreach(), mem.isFailOpen());
        return new AtmosphereAiEndpointRegistrar(framework, properties, guardrails);
    }

    /**
     * Bridges the application's {@link FactResolver} bean (if any) into the
     * framework's properties so {@code AiEndpointHandler} picks it up at turn
     * dispatch. Framework-scoped, lifecycle owned by Spring, no process-wide
     * static.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(FactResolver.class)
    public FactResolverBridge atmosphereFactResolverBridge(
            AtmosphereFramework framework, FactResolver resolver) {
        framework.getAtmosphereConfig().properties()
                .put(FactResolver.FACT_RESOLVER_PROPERTY, resolver);
        logger.info("Bridged Spring FactResolver bean {} into AiEndpointHandler "
                + "(lifecycle: Spring-owned)", resolver.getClass().getName());
        return new FactResolverBridge(resolver);
    }

    /**
     * Marker bean recording that a Spring-managed FactResolver has been bridged
     * into the framework properties.
     */
    public record FactResolverBridge(FactResolver resolver) { }

    /**
     * Opt-in PII redaction guardrail. Off by default — enable with
     * {@code atmosphere.ai.guardrails.pii.enabled=true}. Redacts emails, phone
     * numbers, credit card numbers, and US SSNs from requests and responses;
     * set {@code atmosphere.ai.guardrails.pii.blocking=true} to block instead.
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmospherePiiGuardrail")
    @ConditionalOnProperty(name = "atmosphere.ai.guardrails.pii.enabled", havingValue = "true")
    public PiiRedactionGuardrail atmospherePiiGuardrail(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.pii.blocking:false}") boolean blocking) {
        var g = new PiiRedactionGuardrail();
        if (blocking) {
            logger.info("PiiRedactionGuardrail registered (blocking mode)");
            return g.blocking();
        }
        logger.info("PiiRedactionGuardrail registered (redacting mode)");
        return g;
    }

    /**
     * Opt-in drift / output-length guardrail. Off by default — enable with
     * {@code atmosphere.ai.guardrails.drift.enabled=true}. Blocks responses
     * whose length is beyond {@code z-score-threshold} standard deviations of a
     * rolling window.
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereDriftGuardrail")
    @ConditionalOnProperty(name = "atmosphere.ai.guardrails.drift.enabled", havingValue = "true")
    public OutputLengthZScoreGuardrail atmosphereDriftGuardrail(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.drift.window-size:50}") int windowSize,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.drift.z-score-threshold:3.0}") double zThreshold,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.drift.min-samples:10}") int minSamples) {
        logger.info("OutputLengthZScoreGuardrail registered (window={}, z={}, minSamples={})",
                windowSize, zThreshold, minSamples);
        return new OutputLengthZScoreGuardrail(windowSize, zThreshold, minSamples);
    }

    /**
     * Opt-in per-tenant cost ceiling. Off by default — enable with
     * {@code atmosphere.ai.guardrails.cost.enabled=true} and set a USD ceiling
     * via {@code atmosphere.ai.guardrails.cost.budget-usd}. Enforcement requires
     * a {@link org.atmosphere.ai.cost.TokenPricing} bean (see the
     * cost-accountant installer).
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereCostCeilingGuardrail")
    @ConditionalOnProperty(name = "atmosphere.ai.guardrails.cost.enabled", havingValue = "true")
    public org.atmosphere.ai.guardrails.CostCeilingGuardrail atmosphereCostCeilingGuardrail(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.cost.budget-usd:0.0}") double budgetUsd) {
        logger.info("CostCeilingGuardrail registered (budgetUsd={})", budgetUsd);
        return new org.atmosphere.ai.guardrails.CostCeilingGuardrail(budgetUsd);
    }

    /**
     * Opt-in content-safety moderation guardrail. Off by default — enable with
     * {@code atmosphere.ai.guardrails.moderation.enabled=true}. Detector tier via
     * {@code atmosphere.ai.guardrails.moderation.detector} ({@code rule} default,
     * or {@code llm}); fail-closed by default
     * ({@code ...moderation.fail-open=true} to admit on detector error).
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereModerationGuardrail")
    @ConditionalOnProperty(name = "atmosphere.ai.guardrails.moderation.enabled", havingValue = "true")
    public org.atmosphere.ai.guardrails.ModerationGuardrail atmosphereModerationGuardrail(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.moderation.detector:rule}") String detector,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.moderation.fail-open:false}") boolean failOpen) {
        var llm = "llm".equalsIgnoreCase(detector);
        var guardrail = llm
                ? new org.atmosphere.ai.guardrails.ModerationGuardrail(
                        new org.atmosphere.ai.guardrails.LlmModerationDetector())
                        .scope(org.atmosphere.ai.guardrails.ModerationGuardrail.Scope.REQUEST)
                : new org.atmosphere.ai.guardrails.ModerationGuardrail();
        if (failOpen) {
            guardrail = guardrail.failOpen();
        }
        logger.info("ModerationGuardrail registered (detector={}, failClosed={})",
                llm ? "llm" : "rule", !failOpen);
        return guardrail;
    }

    /**
     * Opt-in OAuth on-behalf-of (RFC 8693 token-exchange) credential vault. Off
     * by default — enable with {@code atmosphere.ai.identity.oauth-obo.enabled=true}
     * and configure the {@code token-endpoint} / {@code client-id} /
     * {@code client-secret}. Set {@code master-key} (base64) to back subject-token
     * storage with the AES-GCM encrypted store (else unencrypted in-memory, with
     * a startup warning).
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereOAuthOboCredentialStore")
    @ConditionalOnProperty(name = "atmosphere.ai.identity.oauth-obo.enabled", havingValue = "true")
    public org.atmosphere.ai.identity.OAuthOnBehalfOfCredentialStore atmosphereOAuthOboCredentialStore(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.token-endpoint}") String tokenEndpoint,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.client-id}") String clientId,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.client-secret:}") String clientSecret,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.default-scope:}") String scope,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.default-audience:}") String audience,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.subject-token-key:oauth.subject_token}") String subjectKey,
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.identity.oauth-obo.master-key:}") String masterKeyB64) {
        org.atmosphere.ai.identity.CredentialStore backing;
        if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
            backing = new org.atmosphere.ai.identity.AtmosphereEncryptedCredentialStore(
                    java.util.Base64.getDecoder().decode(masterKeyB64));
        } else {
            logger.warn("atmosphere.ai.identity.oauth-obo.master-key not set — subject tokens "
                    + "are stored UNENCRYPTED in memory; set a base64 master key for production");
            backing = new org.atmosphere.ai.identity.InMemoryCredentialStore();
        }
        var config = org.atmosphere.ai.identity.OAuthOboConfig.builder(
                        java.net.URI.create(tokenEndpoint), clientId, clientSecret)
                .defaultScope(scope == null || scope.isBlank() ? null : scope)
                .defaultAudience(audience == null || audience.isBlank() ? null : audience)
                .subjectTokenKey(subjectKey)
                .build();
        logger.info("OAuthOnBehalfOfCredentialStore registered (endpoint={}, encryptedBacking={})",
                tokenEndpoint, masterKeyB64 != null && !masterKeyB64.isBlank());
        return new org.atmosphere.ai.identity.OAuthOnBehalfOfCredentialStore(backing, config);
    }

    /**
     * Publishes a {@link org.atmosphere.ai.cost.CostAccountant} into the
     * process-wide holder the {@code AiStreamingSession} decorator chain reads.
     * Priority: user-supplied {@code CostAccountant} bean, else a built-in
     * {@code CostCeilingAccountant} from the cost-ceiling guardrail + token
     * pricing beans, else NOOP (zero overhead).
     */
    @Bean
    @ConditionalOnMissingBean(CostAccountantInstaller.class)
    public CostAccountantInstaller atmosphereCostAccountantInstaller(
            org.springframework.beans.factory.ObjectProvider<org.atmosphere.ai.cost.CostAccountant>
                    userAccountant,
            org.springframework.beans.factory.ObjectProvider<org.atmosphere.ai.guardrails.CostCeilingGuardrail>
                    guardrailProvider,
            org.springframework.beans.factory.ObjectProvider<org.atmosphere.ai.cost.TokenPricing>
                    pricingProvider) {
        var user = userAccountant.getIfAvailable();
        if (user != null) {
            return new CostAccountantInstaller(user, "user-bean");
        }
        var guardrail = guardrailProvider.getIfAvailable();
        var pricing = pricingProvider.getIfAvailable();
        if (guardrail != null && pricing != null) {
            return new CostAccountantInstaller(
                    new org.atmosphere.ai.cost.CostCeilingAccountant(guardrail, pricing),
                    "CostCeilingAccountant(guardrail+pricing)");
        }
        return new CostAccountantInstaller(
                org.atmosphere.ai.cost.CostAccountant.NOOP,
                "NOOP (no CostAccountant, guardrail, or pricing bean)");
    }

    /**
     * Installs a {@link org.atmosphere.ai.cost.CostAccountant} into the
     * process-wide {@link org.atmosphere.ai.cost.CostAccountantHolder} on
     * startup and restores the no-op on shutdown.
     */
    static final class CostAccountantInstaller
            implements org.springframework.beans.factory.SmartInitializingSingleton,
                       org.springframework.beans.factory.DisposableBean {

        private final org.atmosphere.ai.cost.CostAccountant accountant;
        private final String source;

        CostAccountantInstaller(org.atmosphere.ai.cost.CostAccountant accountant, String source) {
            this.accountant = accountant;
            this.source = source;
        }

        @Override
        public void afterSingletonsInstantiated() {
            if (accountant != org.atmosphere.ai.cost.CostAccountant.NOOP) {
                org.atmosphere.ai.cost.CostAccountantHolder.install(accountant);
                logger.info("CostAccountant installed ({}): {}",
                        source, accountant.getClass().getSimpleName());
            } else {
                logger.debug("CostAccountantInstaller: {} — holder stays at NOOP", source);
            }
        }

        @Override
        public void destroy() {
            org.atmosphere.ai.cost.CostAccountantHolder.reset();
        }

        /** Exposed so tests can assert which path fired. */
        String source() {
            return source;
        }

        /** Exposed so tests can assert the resolved accountant. */
        org.atmosphere.ai.cost.CostAccountant accountant() {
            return accountant;
        }
    }

    /**
     * Opt-in crash-durable run resume. Off by default — enable with
     * {@code atmosphere.ai.resume.durable.enabled=true}. Installs a
     * {@code RunJournal}-backed {@code RunRegistry} into the process-wide holder
     * and rehydrates persisted runs on startup. Supply a durable
     * {@code RunJournal} bean for real crash survival; without one the bundled
     * in-memory journal is used (a WARN fires — not crash-durable).
     */
    @Bean
    @ConditionalOnMissingBean(RunRegistryInstaller.class)
    @ConditionalOnProperty(name = "atmosphere.ai.resume.durable.enabled", havingValue = "true")
    public RunRegistryInstaller atmosphereRunRegistryInstaller(
            org.springframework.beans.factory.ObjectProvider<org.atmosphere.ai.resume.RunJournal>
                    journalProvider) {
        var journal = journalProvider.getIfAvailable(org.atmosphere.ai.resume.InMemoryRunJournal::new);
        return new RunRegistryInstaller(journal);
    }

    /**
     * Builds a {@code RunJournal}-backed {@code RunRegistry}, rehydrates
     * persisted runs, installs it into the process-wide holder on startup, and
     * restores the default in-memory registry on shutdown.
     */
    static final class RunRegistryInstaller
            implements org.springframework.beans.factory.SmartInitializingSingleton,
                       org.springframework.beans.factory.DisposableBean {

        private final org.atmosphere.ai.resume.RunJournal journal;

        RunRegistryInstaller(org.atmosphere.ai.resume.RunJournal journal) {
            this.journal = journal;
        }

        @Override
        public void afterSingletonsInstantiated() {
            var registry = new org.atmosphere.ai.resume.RunRegistry(
                    java.time.Clock.systemUTC(),
                    org.atmosphere.ai.resume.RunRegistry.DEFAULT_TTL,
                    journal);
            var rehydrated = registry.rehydrate();
            org.atmosphere.ai.resume.RunRegistryHolder.install(registry);
            if (journal.durable()) {
                logger.info("Crash-durable run resume enabled (journal={}, rehydrated={} run(s))",
                        journal.getClass().getSimpleName(), rehydrated);
            } else {
                logger.warn("Run resume journaling enabled but journal {} is in-memory — "
                                + "NOT crash-durable. Supply a durable RunJournal bean for "
                                + "crash survival. (rehydrated={} run(s))",
                        journal.getClass().getSimpleName(), rehydrated);
            }
        }

        @Override
        public void destroy() {
            org.atmosphere.ai.resume.RunRegistryHolder.reset();
        }

        /** Exposed so tests can assert the resolved journal. */
        org.atmosphere.ai.resume.RunJournal journal() {
            return journal;
        }
    }

    /**
     * Stream-level PII scrubber — the response-path complement to the guardrail.
     * Rewrites each text frame in-flight inside the broadcaster chain before any
     * byte reaches the client. Registered under the same property as the
     * guardrail ({@code atmosphere.ai.guardrails.pii.enabled=true}); default
     * replacement {@code [REDACTED]} (override via
     * {@code atmosphere.ai.guardrails.pii.replacement}).
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmospherePiiStreamFilter")
    @ConditionalOnProperty(name = "atmosphere.ai.guardrails.pii.enabled", havingValue = "true")
    public PiiRedactionFilter atmospherePiiStreamFilter(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.pii.replacement:[REDACTED]}") String replacement) {
        logger.info("PiiRedactionFilter registered (BroadcasterFilter, replacement={})", replacement);
        return new PiiRedactionFilter(replacement);
    }

    /**
     * Installs every {@link org.atmosphere.ai.filter.AiStreamBroadcastFilter}
     * bean on every broadcaster the framework creates (present + future), and
     * removes the installer listener on shutdown (Correctness Invariant #1).
     */
    @Bean
    @ConditionalOnBean(org.atmosphere.ai.filter.AiStreamBroadcastFilter.class)
    public AiStreamFilterInstaller atmosphereAiStreamFilterInstaller(
            AtmosphereFramework framework,
            java.util.List<org.atmosphere.ai.filter.AiStreamBroadcastFilter> filters) {
        return new AiStreamFilterInstaller(framework, filters);
    }

    /**
     * Installs AI stream filters and keeps a handle on both the factory and the
     * listener so the registration can be undone on shutdown.
     */
    static final class AiStreamFilterInstaller
            implements org.springframework.beans.factory.SmartInitializingSingleton,
                       org.springframework.beans.factory.DisposableBean {

        private final AtmosphereFramework framework;
        private final java.util.List<org.atmosphere.ai.filter.AiStreamBroadcastFilter> filters;
        private volatile org.atmosphere.cpr.BroadcasterFactory factoryRef;
        private volatile BroadcasterListener listenerRef;

        AiStreamFilterInstaller(AtmosphereFramework framework,
                java.util.List<org.atmosphere.ai.filter.AiStreamBroadcastFilter> filters) {
            this.framework = framework;
            this.filters = filters;
        }

        @Override
        public void afterSingletonsInstantiated() {
            framework.getAtmosphereConfig().startupHook(f -> {
                var factory = f.getBroadcasterFactory();
                if (factory == null) {
                    logger.warn("BroadcasterFactory not available — cannot install "
                            + "{} stream filter(s) on AI broadcasters", filters.size());
                    return;
                }
                factory.lookupAll().forEach(b -> applyFilters(b, filters));
                var listener = new BroadcasterListener() {
                    @Override public void onPostCreate(Broadcaster b) { applyFilters(b, filters); }
                    @Override public void onComplete(Broadcaster b) { }
                    @Override public void onPreDestroy(Broadcaster b) { }
                    @Override public void onAddAtmosphereResource(Broadcaster b,
                            org.atmosphere.cpr.AtmosphereResource r) { }
                    @Override public void onRemoveAtmosphereResource(Broadcaster b,
                            org.atmosphere.cpr.AtmosphereResource r) { }
                    @Override public void onMessage(Broadcaster b,
                            org.atmosphere.cpr.Deliver d) { }
                };
                factory.addBroadcasterListener(listener);
                this.factoryRef = factory;
                this.listenerRef = listener;
                logger.info("Installed {} AiStreamBroadcastFilter(s) on every broadcaster "
                        + "(present + future)", filters.size());
            });
        }

        @Override
        public void destroy() {
            var factory = factoryRef;
            var listener = listenerRef;
            if (factory != null && listener != null) {
                factory.removeBroadcasterListener(listener);
                logger.info("Removed AiStreamBroadcastFilter installer listener from factory");
            }
            factoryRef = null;
            listenerRef = null;
        }
    }

    private static void applyFilters(Broadcaster b,
            java.util.List<org.atmosphere.ai.filter.AiStreamBroadcastFilter> filters) {
        for (var f : filters) {
            if (!b.getBroadcasterConfig().filters().contains(f)) {
                b.getBroadcasterConfig().addFilter(f);
            }
        }
    }

    @Bean
    FilterRegistrationBean<Filter> atmosphereConsoleFilter() {
        var registration = new FilterRegistrationBean<Filter>(new ConsoleResourceFilter());
        registration.addUrlPatterns("/atmosphere/console/*");
        registration.setOrder(0);
        return registration;
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> atmosphereAiConsoleLog() {
        return event -> {
            int port = event.getWebServer().getPort();
            logger.info("Atmosphere AI console available at http://localhost:{}/atmosphere/console/", port);
        };
    }

    private String resolveApiKey(AtmosphereProperties.AiProperties aiProps) {
        if (aiProps.getApiKey() != null && !aiProps.getApiKey().isBlank()) {
            return aiProps.getApiKey();
        }
        // Fall back to environment variables
        var envKey = env("LLM_API_KEY");
        if (envKey != null) {
            return envKey;
        }
        envKey = env("OPENAI_API_KEY");
        if (envKey != null) {
            return envKey;
        }
        envKey = env("GEMINI_API_KEY");
        if (envKey != null) {
            return envKey;
        }
        return null;
    }

    private static String env(String key) {
        var val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : null;
    }

    /**
     * Opt-in content-based model routing. Off by default — enable with
     * {@code atmosphere.ai.routing.enabled=true}. Wraps the framework-resolved
     * {@link org.atmosphere.ai.llm.LlmClient} in a
     * {@link org.atmosphere.ai.routing.RoutingLlmClient} and installs it via
     * {@link AiConfig#installClient}, so it becomes the client every
     * {@code AgentRuntime} dispatch reads on the request critical path.
     *
     * <p>All four {@code RoutingRule} families are config-driven and compose in
     * the order <strong>content → model → cost → latency</strong> (most-specific
     * first); the router then evaluates them first-match-wins. See
     * {@code modules/ai/README.md} (§ Routing).</p>
     */
    @Bean
    @ConditionalOnMissingBean(RoutingClientInstaller.class)
    @ConditionalOnProperty(name = "atmosphere.ai.routing.enabled", havingValue = "true")
    public RoutingClientInstaller atmosphereRoutingClientInstaller(
            AiConfig.LlmSettings baseSettings, AtmosphereProperties properties) {
        var routing = properties.getAi().getRouting();
        var defaultModel = (routing.getDefaultModel() != null && !routing.getDefaultModel().isBlank())
                ? routing.getDefaultModel()
                : baseSettings.model();
        var builder = org.atmosphere.ai.routing.RoutingLlmClient.builder(
                baseSettings.client(), defaultModel);
        java.util.function.BiFunction<String, String, org.atmosphere.ai.llm.LlmClient> resolver =
                (baseUrl, apiKey) -> resolveRuleTarget(baseUrl, apiKey, baseSettings);
        int ruleCount = 0;

        // 1. content rules — most specific (matches on the user message body).
        for (var rule : routing.getContentRules()) {
            if (rule.getModel() == null || rule.getModel().isBlank()
                    || rule.getKeywords() == null || rule.getKeywords().isEmpty()) {
                logger.warn("Skipping content routing rule with no model or no keywords: model={}, keywords={}",
                        rule.getModel(), rule.getKeywords());
                continue;
            }
            var keywords = java.util.List.copyOf(rule.getKeywords());
            var target = resolveRuleTarget(rule.getBaseUrl(), rule.getApiKey(), baseSettings);
            builder.route(org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.contentBased(
                    msg -> keywordsMatchCaseInsensitive(msg, keywords),
                    target, rule.getModel()));
            ruleCount++;
        }

        // 2. model rules — literal case-insensitive equals on request.model().
        for (var rule : routing.getModelRules()) {
            if (rule.getModelPattern() == null || rule.getModelPattern().isBlank()) {
                logger.warn("Skipping model routing rule with blank model-pattern");
                continue;
            }
            var pattern = rule.getModelPattern();
            var target = resolveRuleTarget(rule.getBaseUrl(), rule.getApiKey(), baseSettings);
            builder.route(org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.modelBased(
                    m -> m != null && m.equalsIgnoreCase(pattern), target));
            ruleCount++;
        }

        // 3. cost rules — highest-capability model within the cost budget.
        for (var rule : routing.getCostRules()) {
            if (rule.getMaxCost() == null || rule.getModels() == null || rule.getModels().isEmpty()) {
                logger.warn("Skipping cost routing rule with null max-cost or empty models: maxCost={}",
                        rule.getMaxCost());
                continue;
            }
            var options = rule.getModels().stream()
                    .map(o -> o.toModelOption(resolver))
                    .toList();
            builder.route(org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.costBased(
                    rule.getMaxCost(), options));
            ruleCount++;
        }

        // 4. latency rules — highest-capability model within the latency budget.
        for (var rule : routing.getLatencyRules()) {
            if (rule.getMaxLatencyMs() == null || rule.getModels() == null || rule.getModels().isEmpty()) {
                logger.warn("Skipping latency routing rule with null max-latency-ms or empty models: maxLatencyMs={}",
                        rule.getMaxLatencyMs());
                continue;
            }
            var options = rule.getModels().stream()
                    .map(o -> o.toModelOption(resolver))
                    .toList();
            builder.route(org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.latencyBased(
                    rule.getMaxLatencyMs(), options));
            ruleCount++;
        }

        var router = builder.build();
        AiConfig.installClient(router);
        logger.info("Atmosphere AI routing enabled: wrapped resolved client in RoutingLlmClient with "
                + "{} rule(s) [content={}, model={}, cost={}, latency={}], defaultModel={}",
                ruleCount, routing.getContentRules().size(), routing.getModelRules().size(),
                routing.getCostRules().size(), routing.getLatencyRules().size(), defaultModel);
        return new RoutingClientInstaller(router, baseSettings.client(), ruleCount);
    }

    /**
     * Resolve the target {@link org.atmosphere.ai.llm.LlmClient} for a routing
     * rule (or cost/latency model option). When {@code ruleBaseUrl} and/or
     * {@code ruleApiKey} are set it gets a dedicated
     * {@link org.atmosphere.ai.llm.OpenAiCompatibleClient} (falling back to the
     * resolved base URL / key for the component the rule omits); otherwise it
     * reuses the framework-resolved client so the rule only changes the model
     * name.
     */
    private static org.atmosphere.ai.llm.LlmClient resolveRuleTarget(
            String ruleBaseUrl, String ruleApiKey, AiConfig.LlmSettings baseSettings) {
        var hasBaseUrl = ruleBaseUrl != null && !ruleBaseUrl.isBlank();
        var hasApiKey = ruleApiKey != null && !ruleApiKey.isBlank();
        if (!hasBaseUrl && !hasApiKey) {
            return baseSettings.client();
        }
        var clientBuilder = org.atmosphere.ai.llm.OpenAiCompatibleClient.builder()
                .baseUrl(hasBaseUrl ? ruleBaseUrl : baseSettings.baseUrl());
        var key = hasApiKey ? ruleApiKey : baseSettings.apiKey();
        if (key != null && !key.isBlank()) {
            clientBuilder.apiKey(key);
        }
        return clientBuilder.build();
    }

    /**
     * Case-insensitive substring match: {@code true} if {@code message}
     * contains any of {@code keywords}. A {@code null}/empty message matches
     * nothing.
     */
    private static boolean keywordsMatchCaseInsensitive(String message, java.util.List<String> keywords) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        var lower = message.toLowerCase(java.util.Locale.ROOT);
        for (var kw : keywords) {
            if (kw != null && !kw.isEmpty()
                    && lower.contains(kw.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marker bean recording the installed {@link org.atmosphere.ai.routing.RoutingLlmClient}
     * and the un-wrapped resolved client it decorates. Restores the resolved
     * client into {@link AiConfig} on shutdown so the process-wide singleton
     * does not carry the router across context restarts (Correctness
     * Invariant #1 — every install has a symmetric uninstall).
     */
    static final class RoutingClientInstaller
            implements org.springframework.beans.factory.DisposableBean {

        private final org.atmosphere.ai.routing.RoutingLlmClient router;
        private final org.atmosphere.ai.llm.LlmClient resolvedClient;
        private final int ruleCount;

        RoutingClientInstaller(org.atmosphere.ai.routing.RoutingLlmClient router,
                org.atmosphere.ai.llm.LlmClient resolvedClient, int ruleCount) {
            this.router = router;
            this.resolvedClient = resolvedClient;
            this.ruleCount = ruleCount;
        }

        @Override
        public void destroy() {
            // Only restore if our router is still the installed client — a
            // later install (another decorator) takes precedence and must not
            // be clobbered on our shutdown.
            var current = AiConfig.get();
            if (current != null && current.client() == router) {
                AiConfig.installClient(resolvedClient);
                logger.info("Restored un-wrapped resolved client on routing installer shutdown");
            }
        }

        /** Exposed so tests can assert the installed router. */
        org.atmosphere.ai.routing.RoutingLlmClient router() {
            return router;
        }

        /** Exposed so tests can assert the rule count. */
        int ruleCount() {
            return ruleCount;
        }
    }

    /**
     * Serves built-in console static assets from {@code META-INF/resources/atmosphere/console/}
     * before the Atmosphere servlet (mapped to {@code /atmosphere/*}) can intercept them.
     */
    static class ConsoleResourceFilter implements Filter {

        private static final String CONSOLE_PREFIX = "/atmosphere/console";
        private static final String RESOURCE_BASE = "META-INF/resources/atmosphere/console/";

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var httpReq = (HttpServletRequest) request;
            var httpRes = (HttpServletResponse) response;
            var path = httpReq.getRequestURI();

            if (!path.startsWith(CONSOLE_PREFIX)) {
                chain.doFilter(request, response);
                return;
            }

            // Extract the relative path after /atmosphere/console/
            var relativePath = path.substring(CONSOLE_PREFIX.length());
            if (relativePath.isEmpty() || relativePath.equals("/")) {
                relativePath = "/index.html";
            }

            // Reject path traversal attempts
            if (relativePath.contains("..")) {
                httpRes.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // Strip leading slash for classpath lookup
            var resourceName = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
            var resourcePath = RESOURCE_BASE + resourceName;

            InputStream resource = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath);
            if (resource != null) {
                try (resource) {
                    httpRes.setContentType(guessContentType(resourceName));
                    resource.transferTo(httpRes.getOutputStream());
                }
                return;
            }

            chain.doFilter(request, response);
        }

        private String guessContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
