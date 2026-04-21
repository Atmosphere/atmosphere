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

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.facts.FactResolver;
import org.atmosphere.ai.filter.PiiRedactionFilter;
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
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
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
        var model = resolveModel(aiProps);
        var baseUrl = resolveBaseUrl(aiProps);
        var mode = resolveMode(aiProps);
        if (apiKey == null && !"local".equalsIgnoreCase(mode)) {
            if (aiProps.isFailFast()) {
                throw new IllegalStateException(
                        "Atmosphere AI is configured to fail-fast but no API key was found. "
                                + "Set atmosphere.ai.api-key / LLM_API_KEY / OPENAI_API_KEY / "
                                + "GEMINI_API_KEY, or set atmosphere.ai.fail-fast=false to boot "
                                + "without credentials (dev mode only).");
            }
            logger.warn("No AI API key configured. Set atmosphere.ai.api-key, "
                    + "LLM_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY environment variable "
                    + "(set atmosphere.ai.fail-fast=true to refuse startup on missing keys)");
        }
        var settings = AiConfig.configure(mode, model, apiKey, baseUrl);
        // Drop any cached runtime resolution so DemoAgentRuntime (whose
        // isAvailable depends on AiConfig) is evaluated against the config
        // we just wrote, not the null config the cache may have frozen.
        org.atmosphere.ai.AgentRuntimeResolver.reset();
        logger.info("Atmosphere AI configured: mode={}, model={}", mode, model);
        return settings;
    }

    private String resolveMode(AtmosphereProperties.AiProperties aiProps) {
        if (aiProps.getMode() != null && !aiProps.getMode().isBlank()) {
            return aiProps.getMode();
        }
        var envMode = env("LLM_MODE");
        return envMode != null ? envMode : AiConfig.DEFAULT_MODE;
    }

    private String resolveModel(AtmosphereProperties.AiProperties aiProps) {
        if (aiProps.getModel() != null && !aiProps.getModel().isBlank()) {
            return aiProps.getModel();
        }
        var envModel = env("LLM_MODEL");
        return envModel != null ? envModel : AiConfig.DEFAULT_MODEL;
    }

    private String resolveBaseUrl(AtmosphereProperties.AiProperties aiProps) {
        if (aiProps.getBaseUrl() != null && !aiProps.getBaseUrl().isBlank()) {
            return aiProps.getBaseUrl();
        }
        return env("LLM_BASE_URL");
    }

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereAiEndpointRegistrar atmosphereAiEndpointRegistrar(
            AtmosphereFramework framework,
            AtmosphereProperties properties,
            org.springframework.beans.factory.ObjectProvider<AiGuardrail> guardrailProvider) {
        var guardrails = guardrailProvider.orderedStream().toList();
        // Bridge the bean list into framework properties so user-defined
        // @AiEndpoint classes (which go through AiEndpointProcessor, not
        // this registrar) also pick them up. Without this publish, the
        // registrar applied guardrails to the default endpoint only and
        // @AiEndpoint paths were starved.
        if (!guardrails.isEmpty()) {
            framework.getAtmosphereConfig().properties()
                    .put(AiGuardrail.GUARDRAILS_PROPERTY, guardrails);
            logger.info("Bridged {} Spring AiGuardrail bean(s) into framework properties: {}",
                    guardrails.size(),
                    guardrails.stream().map(g -> g.getClass().getSimpleName()).toList());
        }
        return new AtmosphereAiEndpointRegistrar(framework, properties, guardrails);
    }

    /**
     * Bridges the application's {@link FactResolver} bean (if any) into the
     * framework's properties so {@code AiEndpointHandler} picks it up at
     * turn dispatch. Same template as
     * {@code AtmosphereCoordinatorAutoConfiguration.atmosphereCoordinatorJournalBridge}
     * — framework-scoped, lifecycle owned by Spring, no process-wide static.
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
     * Marker bean recording that a Spring-managed FactResolver has been
     * bridged into the framework properties. The bean carries the resolver
     * reference so tests and diagnostics can verify the bridge was wired
     * without poking at framework properties directly.
     */
    public record FactResolverBridge(FactResolver resolver) { }

    /**
     * Opt-in PII redaction guardrail. Off by default — enable with
     * {@code atmosphere.ai.guardrails.pii.enabled=true}. Redacts emails,
     * phone numbers, credit card numbers, and US SSNs from requests and
     * responses; set {@code atmosphere.ai.guardrails.pii.blocking=true} to
     * block instead of redact.
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
     * {@code atmosphere.ai.guardrails.drift.enabled=true}. Maintains a
     * rolling window of response lengths and blocks outliers beyond
     * {@code atmosphere.ai.guardrails.drift.z-score-threshold} standard
     * deviations.
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
     * {@code atmosphere.ai.guardrails.cost.enabled=true} and set a USD
     * ceiling via {@code atmosphere.ai.guardrails.cost.budget-usd}.
     * Enforcement only kicks in when both this bean AND a
     * {@link org.atmosphere.ai.cost.TokenPricing} bean are present — without
     * pricing the framework cannot compute per-turn cost and {@code addCost}
     * would stay at zero, so exposing the guardrail alone would silently
     * publish a dashboard that never triggers.
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
     * Publishes a {@link org.atmosphere.ai.cost.CostAccountant} into the
     * process-wide holder the {@code AiStreamingSession} decorator chain
     * reads. Priority:
     *
     * <ol>
     *   <li>A user-supplied {@link org.atmosphere.ai.cost.CostAccountant}
     *       bean (custom attribution — ledger, Micrometer, third-party
     *       billing).</li>
     *   <li>A built-in {@link org.atmosphere.ai.cost.CostCeilingAccountant}
     *       composed from the
     *       {@link org.atmosphere.ai.guardrails.CostCeilingGuardrail} +
     *       {@link org.atmosphere.ai.cost.TokenPricing} beans — this is
     *       the production consumer that closes the
     *       {@code addCost} integration loop.</li>
     * </ol>
     *
     * <p>Without at least one of these paths the holder stays at its
     * no-op default and {@code CostAccountingSession} never wraps the
     * outgoing session — zero runtime overhead for deployments that do
     * not track cost.</p>
     *
     * <p>{@link org.springframework.beans.factory.DisposableBean}
     * restores the no-op on shutdown so the holder does not carry stale
     * state across hot-redeploy and test-context restarts.</p>
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
        // Nothing to wire — return an installer that leaves the holder at NOOP.
        return new CostAccountantInstaller(
                org.atmosphere.ai.cost.CostAccountant.NOOP, "NOOP (no CostAccountant, guardrail, or pricing bean)");
    }

    /**
     * Installs a {@link org.atmosphere.ai.cost.CostAccountant} into the
     * process-wide {@link org.atmosphere.ai.cost.CostAccountantHolder} on
     * startup and restores the no-op on shutdown so the
     * {@code AiStreamingSession} decorator chain stays consistent across
     * context restarts.
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
     * Stream-level PII scrubber — the response-path complement to the
     * guardrail. Unlike the guardrail (which can only early-terminate
     * after tokens have flushed), this filter rewrites each text frame
     * in-flight inside the broadcaster chain before any byte reaches
     * the client. Atmosphere owns the broadcaster; no other AI
     * framework can do per-token redaction without controlling the
     * transport.
     *
     * <p>Registered under the same property as the guardrail
     * ({@code atmosphere.ai.guardrails.pii.enabled=true}) so operators
     * opt into the full PII story with one flag. Default replacement
     * text is {@code [REDACTED]}; override via
     * {@code atmosphere.ai.guardrails.pii.replacement}.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmospherePiiStreamFilter")
    @ConditionalOnProperty(name = "atmosphere.ai.guardrails.pii.enabled", havingValue = "true")
    public PiiRedactionFilter atmospherePiiStreamFilter(
            @org.springframework.beans.factory.annotation.Value(
                    "${atmosphere.ai.guardrails.pii.replacement:[REDACTED]}") String replacement) {
        logger.info("PiiRedactionFilter registered (BroadcasterFilter, replacement={})",
                replacement);
        return new PiiRedactionFilter(replacement);
    }

    /**
     * Installs every {@link org.atmosphere.ai.filter.AiStreamBroadcastFilter}
     * bean (PII, or user-defined) on every broadcaster created by the
     * framework — present and future. Subscribes to
     * {@link BroadcasterListener#onPostCreate} so late-attached
     * broadcasters also pick up the filter without the app re-binding.
     *
     * <p>This is the response-path delivery of the guardrail promise —
     * rewriting tokens in-flight, not terminating after the fact.</p>
     *
     * <p>Implements {@link org.springframework.beans.factory.DisposableBean}
     * so Spring calls {@code destroy()} on context shutdown and the listener
     * is removed from the factory (Correctness Invariant #1 — every
     * registration has an explicit uninstall). Without this, test
     * harnesses and embedded redeploys accumulate stale installer
     * listeners on the same factory.</p>
     */
    @Bean
    @ConditionalOnBean(org.atmosphere.ai.filter.AiStreamBroadcastFilter.class)
    public AiStreamFilterInstaller atmosphereAiStreamFilterInstaller(
            AtmosphereFramework framework,
            java.util.List<org.atmosphere.ai.filter.AiStreamBroadcastFilter> filters) {
        return new AiStreamFilterInstaller(framework, filters);
    }

    /**
     * Installs AI stream filters and keeps a handle on both the factory and
     * the listener so the registration can be undone on shutdown.
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

}
