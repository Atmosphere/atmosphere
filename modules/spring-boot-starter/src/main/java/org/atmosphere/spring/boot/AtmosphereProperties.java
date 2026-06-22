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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * Spring Boot configuration properties for the Atmosphere framework, bound to the
 * {@code atmosphere.*} namespace. Includes settings for servlet path, package scanning,
 * session support, broadcaster configuration, heartbeat interval, and nested
 * gRPC / durable sessions properties.
 */
@ConfigurationProperties(prefix = "atmosphere")
public class AtmosphereProperties {

    private String servletPath = "/atmosphere/*";

    private String packages;

    private int order = 0;

    private boolean sessionSupport = false;

    private String broadcasterClass;

    private String broadcasterCacheClass;

    private Boolean websocketSupport;

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration heartbeatInterval;

    private DurableSessionsProperties durableSessions = new DurableSessionsProperties();

    private Map<String, String> initParams = new HashMap<>();

    private GrpcProperties grpc = new GrpcProperties();

    private AiProperties ai = new AiProperties();

    private WebTransportProperties webTransport = new WebTransportProperties();

    public String getServletPath() {
        return servletPath;
    }

    public void setServletPath(String servletPath) {
        this.servletPath = servletPath;
    }

    public String getPackages() {
        return packages;
    }

    public void setPackages(String packages) {
        this.packages = packages;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public boolean isSessionSupport() {
        return sessionSupport;
    }

    public void setSessionSupport(boolean sessionSupport) {
        this.sessionSupport = sessionSupport;
    }

    public String getBroadcasterClass() {
        return broadcasterClass;
    }

    public void setBroadcasterClass(String broadcasterClass) {
        this.broadcasterClass = broadcasterClass;
    }

    public String getBroadcasterCacheClass() {
        return broadcasterCacheClass;
    }

    public void setBroadcasterCacheClass(String broadcasterCacheClass) {
        this.broadcasterCacheClass = broadcasterCacheClass;
    }

    public Boolean getWebsocketSupport() {
        return websocketSupport;
    }

    public void setWebsocketSupport(Boolean websocketSupport) {
        this.websocketSupport = websocketSupport;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public void setInitParams(Map<String, String> initParams) {
        this.initParams = initParams;
    }

    public DurableSessionsProperties getDurableSessions() {
        return durableSessions;
    }

    public void setDurableSessions(DurableSessionsProperties durableSessions) {
        this.durableSessions = durableSessions;
    }

    public GrpcProperties getGrpc() {
        return grpc;
    }

    public void setGrpc(GrpcProperties grpc) {
        this.grpc = grpc;
    }

    public AiProperties getAi() {
        return ai;
    }

    public void setAi(AiProperties ai) {
        this.ai = ai;
    }

    public WebTransportProperties getWebTransport() {
        return webTransport;
    }

    public void setWebTransport(WebTransportProperties webTransport) {
        this.webTransport = webTransport;
    }

    public static class GrpcProperties {

        private boolean enabled = false;

        private int port = 9090;

        private boolean enableReflection = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isEnableReflection() {
            return enableReflection;
        }

        public void setEnableReflection(boolean enableReflection) {
            this.enableReflection = enableReflection;
        }
    }

    public static class DurableSessionsProperties {

        private boolean enabled = false;

        @DurationUnit(ChronoUnit.MINUTES)
        private Duration sessionTtl = Duration.ofMinutes(1440);

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration cleanupInterval = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }
    }

    private String consoleSubtitle = "";

    /** Override the Atmosphere endpoint the console connects to.
     *  When set, the console uses this path instead of auto-detecting. */
    private String consoleEndpoint = "";

    /** Whether to register admin write tools as MCP tools (default false). */
    private String adminMcpWriteTools = "false";

    /** Dedicated origin the console uses to host the MCP Apps sandbox proxy
     *  (SEP-1865). When set (e.g. {@code https://mcp-sandbox.example.com}), the
     *  console loads untrusted MCP App HTML through a proxy iframe at this
     *  origin, which MUST differ from the console's own origin. The configured
     *  origin MUST serve the console's {@code /atmosphere/console/sandbox.html}.
     *  When blank, the console derives a distinct dev origin (localhost ↔
     *  127.0.0.1) and otherwise falls back to the opaque-origin direct sandbox. */
    private String mcpSandboxOrigin = "";

    public String getConsoleSubtitle() {
        return consoleSubtitle;
    }

    public void setConsoleSubtitle(String consoleSubtitle) {
        this.consoleSubtitle = consoleSubtitle;
    }

    public String getConsoleEndpoint() {
        return consoleEndpoint;
    }

    public void setConsoleEndpoint(String consoleEndpoint) {
        this.consoleEndpoint = consoleEndpoint;
    }

    public String getMcpSandboxOrigin() {
        return mcpSandboxOrigin;
    }

    public void setMcpSandboxOrigin(String mcpSandboxOrigin) {
        this.mcpSandboxOrigin = mcpSandboxOrigin;
    }

    public String getAdminMcpWriteTools() {
        return adminMcpWriteTools;
    }

    public void setAdminMcpWriteTools(String adminMcpWriteTools) {
        this.adminMcpWriteTools = adminMcpWriteTools;
    }

    public static class AiProperties {

        private boolean enabled = true;

        // Null defaults so LLM_MODE / LLM_MODEL / LLM_BASE_URL env vars can win over
        // framework fallbacks in AtmosphereAiAutoConfiguration.
        private String mode;

        private String model;

        private String apiKey;

        private String baseUrl;

        private String path = "/atmosphere/ai-chat";

        private String systemPrompt = "You are a helpful assistant.";

        private String systemPromptResource;

        private boolean conversationMemory = true;

        private int maxHistoryMessages = 20;

        private long timeout = 120_000L;

        /**
         * When {@code true}, the Spring Boot starter refuses to boot if the
         * AI key can't be resolved from properties or the env-var chain.
         * Default {@code false} so tests and keyless dev samples
         * (spring-boot-chat, coding-agent, …) still boot; production
         * deployments should set {@code atmosphere.ai.fail-fast=true} so
         * misconfig surfaces at startup rather than at the first LLM call.
         * The earlier v4.0.39-SNAPSHOT default of {@code true} broke every
         * existing sample's test matrix — keep default-off, loud WARN on,
         * and let operators opt in.
         */
        private boolean failFast;

        private RoutingProperties routing = new RoutingProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public RoutingProperties getRouting() {
            return routing;
        }

        public void setRouting(RoutingProperties routing) {
            this.routing = routing;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public String getSystemPromptResource() {
            return systemPromptResource;
        }

        public void setSystemPromptResource(String systemPromptResource) {
            this.systemPromptResource = systemPromptResource;
        }

        public boolean isConversationMemory() {
            return conversationMemory;
        }

        public void setConversationMemory(boolean conversationMemory) {
            this.conversationMemory = conversationMemory;
        }

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * Property-driven model routing, bound to {@code atmosphere.ai.routing.*}.
     * When {@link #enabled} is {@code true}, the Spring Boot starter wraps the
     * resolved LLM client in a
     * {@link org.atmosphere.ai.routing.RoutingLlmClient} so requests route to
     * alternate models by content, model name, cost budget, or latency budget.
     * Off by default — the resolved client is left untouched, so the default
     * path is byte-identical to today's behavior.
     *
     * <p>All four {@code RoutingRule} families are config-driven. Rules compose
     * into the router in the documented order
     * <strong>content → model → cost → latency</strong> (most-specific intent
     * first); the {@link org.atmosphere.ai.routing.RoutingLlmClient} evaluates
     * them first-match-wins in that order. See {@code modules/ai/README.md}
     * (§ Routing).</p>
     */
    public static class RoutingProperties {

        private boolean enabled = false;

        /**
         * Default model the router falls back to when no rule matches.
         * Optional — when blank the resolved {@code AiConfig} model is used.
         */
        private String defaultModel;

        private java.util.List<ContentRule> contentRules = new java.util.ArrayList<>();

        private java.util.List<ModelRule> modelRules = new java.util.ArrayList<>();

        private java.util.List<CostRule> costRules = new java.util.ArrayList<>();

        private java.util.List<LatencyRule> latencyRules = new java.util.ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public java.util.List<ContentRule> getContentRules() {
            return contentRules;
        }

        public void setContentRules(java.util.List<ContentRule> contentRules) {
            this.contentRules = contentRules;
        }

        public java.util.List<ModelRule> getModelRules() {
            return modelRules;
        }

        public void setModelRules(java.util.List<ModelRule> modelRules) {
            this.modelRules = modelRules;
        }

        public java.util.List<CostRule> getCostRules() {
            return costRules;
        }

        public void setCostRules(java.util.List<CostRule> costRules) {
            this.costRules = costRules;
        }

        public java.util.List<LatencyRule> getLatencyRules() {
            return latencyRules;
        }

        public void setLatencyRules(java.util.List<LatencyRule> latencyRules) {
            this.latencyRules = latencyRules;
        }
    }

    /**
     * A single content-based routing rule. When the latest user message
     * contains any of {@link #keywords} (case-insensitive substring match),
     * the request is routed to {@link #model}. By default the rule reuses the
     * framework-resolved client (same provider/credentials); set
     * {@link #baseUrl} and/or {@link #apiKey} to target a different
     * OpenAI-compatible endpoint for this rule.
     */
    public static class ContentRule {

        private java.util.List<String> keywords = new java.util.ArrayList<>();

        private String model;

        private String baseUrl;

        private String apiKey;

        public java.util.List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(java.util.List<String> keywords) {
            this.keywords = keywords;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * A single model-name routing rule, bound to
     * {@code atmosphere.ai.routing.model-rules[i]}. When the incoming request's
     * model name {@link String#equalsIgnoreCase(String) equals (case-insensitive)}
     * {@link #modelPattern}, the request is routed <em>unchanged</em> (the model
     * name is NOT rewritten) to the rule's target client. By default the rule
     * reuses the framework-resolved client; set {@link #baseUrl} and/or
     * {@link #apiKey} to target a dedicated OpenAI-compatible endpoint.
     *
     * <p>The match is a literal case-insensitive equals, not a regex — this
     * keeps the property contract predictable (no accidental
     * metacharacter-driven over-matching from operator config) and mirrors the
     * single-model dispatch intent of {@code RoutingRule.modelBased}.</p>
     */
    public static class ModelRule {

        private String modelPattern;

        private String baseUrl;

        private String apiKey;

        public String getModelPattern() {
            return modelPattern;
        }

        public void setModelPattern(String modelPattern) {
            this.modelPattern = modelPattern;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * A single cost-budget routing rule, bound to
     * {@code atmosphere.ai.routing.cost-rules[i]}. The router selects the
     * highest-{@link ModelOptionProperties#capability capability} model from
     * {@link #models} whose total cost
     * ({@code costPerStreamingText * request.maxStreamingTexts()}) fits within
     * {@link #maxCost}. A rule with a {@code null} {@link #maxCost} or empty
     * {@link #models} is skipped with a {@code WARN} at startup.
     */
    public static class CostRule {

        private Double maxCost;

        private java.util.List<ModelOptionProperties> models = new java.util.ArrayList<>();

        public Double getMaxCost() {
            return maxCost;
        }

        public void setMaxCost(Double maxCost) {
            this.maxCost = maxCost;
        }

        public java.util.List<ModelOptionProperties> getModels() {
            return models;
        }

        public void setModels(java.util.List<ModelOptionProperties> models) {
            this.models = models;
        }
    }

    /**
     * A single latency-budget routing rule, bound to
     * {@code atmosphere.ai.routing.latency-rules[i]}. The router selects the
     * highest-{@link ModelOptionProperties#capability capability} model from
     * {@link #models} whose {@code averageLatencyMs} is within
     * {@link #maxLatencyMs}. A rule with a {@code null} {@link #maxLatencyMs} or
     * empty {@link #models} is skipped with a {@code WARN} at startup.
     */
    public static class LatencyRule {

        private Long maxLatencyMs;

        private java.util.List<ModelOptionProperties> models = new java.util.ArrayList<>();

        public Long getMaxLatencyMs() {
            return maxLatencyMs;
        }

        public void setMaxLatencyMs(Long maxLatencyMs) {
            this.maxLatencyMs = maxLatencyMs;
        }

        public java.util.List<ModelOptionProperties> getModels() {
            return models;
        }

        public void setModels(java.util.List<ModelOptionProperties> models) {
            this.models = models;
        }
    }

    /**
     * A candidate model with cost/latency/capability metadata, bound to a
     * {@code models[j]} entry under a cost- or latency-rule. Maps to the router's
     * {@link org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.ModelOption}
     * record (fields {@code client}, {@code model}, {@code costPerStreamingText},
     * {@code averageLatencyMs}, {@code capability}) via {@link #toModelOption}.
     *
     * <p>The option's {@code client} is built with the same fallback as content
     * rules: when both {@link #baseUrl} and {@link #apiKey} are blank the
     * framework-resolved client is reused (only the model name changes);
     * otherwise a dedicated OpenAI-compatible client is built, falling back to
     * the resolved base URL / key for whichever component is omitted.</p>
     */
    public static class ModelOptionProperties {

        private String model;

        private Double costPerStreamingText;

        private Long averageLatencyMs;

        private Integer capability;

        private String baseUrl;

        private String apiKey;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getCostPerStreamingText() {
            return costPerStreamingText;
        }

        public void setCostPerStreamingText(Double costPerStreamingText) {
            this.costPerStreamingText = costPerStreamingText;
        }

        public Long getAverageLatencyMs() {
            return averageLatencyMs;
        }

        public void setAverageLatencyMs(Long averageLatencyMs) {
            this.averageLatencyMs = averageLatencyMs;
        }

        public Integer getCapability() {
            return capability;
        }

        public void setCapability(Integer capability) {
            this.capability = capability;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Build the router {@code ModelOption} from these properties. Null
         * numeric defaults: {@code capability=0}, {@code costPerStreamingText=0.0},
         * {@code averageLatencyMs=0}. The option's {@code client} is resolved by
         * {@code resolver} — the autoconfig passes its {@code resolveRuleTarget}
         * fallback so {@code base-url}/{@code api-key} (or their absence) are
         * honored identically to content/model rules.
         *
         * @param resolver resolves this option's optional {@code base-url}/{@code api-key}
         *                 (or their absence) to a concrete {@code LlmClient}
         * @return the populated router {@code ModelOption}
         */
        public org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.ModelOption toModelOption(
                java.util.function.BiFunction<String, String,
                        org.atmosphere.ai.llm.LlmClient> resolver) {
            var client = resolver.apply(baseUrl, apiKey);
            return new org.atmosphere.ai.routing.RoutingLlmClient.RoutingRule.ModelOption(
                    client,
                    model,
                    costPerStreamingText != null ? costPerStreamingText : 0.0,
                    averageLatencyMs != null ? averageLatencyMs : 0L,
                    capability != null ? capability : 0);
        }
    }

    /**
     * Configuration properties for WebTransport over HTTP/3, bound to
     * {@code atmosphere.web-transport.*}.
     */
    public static class WebTransportProperties {

        private boolean enabled = false;

        private int port = 4443;

        private String host = "0.0.0.0";

        private boolean addAltSvc = true;

        private SslProperties ssl = new SslProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public boolean isAddAltSvc() {
            return addAltSvc;
        }

        public void setAddAltSvc(boolean addAltSvc) {
            this.addAltSvc = addAltSvc;
        }

        public SslProperties getSsl() {
            return ssl;
        }

        public void setSsl(SslProperties ssl) {
            this.ssl = ssl;
        }

        public static class SslProperties {

            private String certificate;

            private String privateKey;

            private String privateKeyPassword;

            public String getCertificate() {
                return certificate;
            }

            public void setCertificate(String certificate) {
                this.certificate = certificate;
            }

            public String getPrivateKey() {
                return privateKey;
            }

            public void setPrivateKey(String privateKey) {
                this.privateKey = privateKey;
            }

            public String getPrivateKeyPassword() {
                return privateKeyPassword;
            }

            public void setPrivateKeyPassword(String privateKeyPassword) {
                this.privateKeyPassword = privateKeyPassword;
            }
        }
    }
}
