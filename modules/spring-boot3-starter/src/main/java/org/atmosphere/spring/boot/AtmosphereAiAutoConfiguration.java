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
import org.atmosphere.cpr.AtmosphereFramework;
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
            AtmosphereProperties properties) {
        return new AtmosphereAiEndpointRegistrar(framework, properties);
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
