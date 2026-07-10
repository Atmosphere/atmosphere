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

    private DurableRunsProperties durableRuns = new DurableRunsProperties();

    private Map<String, String> initParams = new HashMap<>();

    private GrpcProperties grpc = new GrpcProperties();

    private AiProperties ai = new AiProperties();

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

    public DurableRunsProperties getDurableRuns() {
        return durableRuns;
    }

    public void setDurableRuns(DurableRunsProperties durableRuns) {
        this.durableRuns = durableRuns;
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

    /** Whether to register admin write tools as MCP tools (default false). */
    private String adminMcpWriteTools = "false";

    public String getConsoleSubtitle() {
        return consoleSubtitle;
    }

    public void setConsoleSubtitle(String consoleSubtitle) {
        this.consoleSubtitle = consoleSubtitle;
    }

    public String getAdminMcpWriteTools() {
        return adminMcpWriteTools;
    }

    public void setAdminMcpWriteTools(String adminMcpWriteTools) {
        this.adminMcpWriteTools = adminMcpWriteTools;
    }

    public static class AiProperties {

        private boolean enabled = true;

        private String mode = "remote";

        private String model = "gemini-2.5-flash";

        private String apiKey;

        private String baseUrl;

        private String path = "/atmosphere/ai-chat";

        private String systemPrompt = "You are a helpful assistant.";

        private String systemPromptResource;

        private boolean conversationMemory = true;

        private int maxHistoryMessages = 20;

        private long timeout = 120_000L;

        private RoutingProperties routing = new RoutingProperties();

        private Rag rag = new Rag();

        private Memory memory = new Memory();

        private HarnessProperties harness = new HarnessProperties();

        private CodeProperties code = new CodeProperties();

        private TapeProperties tape = new TapeProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public HarnessProperties getHarness() {
            return harness;
        }

        public void setHarness(HarnessProperties harness) {
            this.harness = harness;
        }

        public CodeProperties getCode() {
            return code;
        }

        public void setCode(CodeProperties code) {
            this.code = code;
        }

        public TapeProperties getTape() {
            return tape;
        }

        public void setTape(TapeProperties tape) {
            this.tape = tape;
        }

        public RoutingProperties getRouting() {
            return routing;
        }

        public void setRouting(RoutingProperties routing) {
            this.routing = routing;
        }

        public Rag getRag() {
            return rag;
        }

        public void setRag(Rag rag) {
            this.rag = rag;
        }

        public Memory getMemory() {
            return memory;
        }

        public void setMemory(Memory memory) {
            this.memory = memory;
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
     * RAG configuration group, bound to {@code atmosphere.ai.rag.*}. Currently
     * carries the injection-safety screen ({@code atmosphere.ai.rag.safety.*}).
     */
    public static class Rag {

        private Safety safety = new Safety();

        public Safety getSafety() {
            return safety;
        }

        public void setSafety(Safety safety) {
            this.safety = safety;
        }

        /**
         * RAG injection-safety screen, bound to {@code atmosphere.ai.rag.safety.*}.
         * Every {@code @AiEndpoint} {@code ContextProvider} is wrapped so retrieved
         * documents are checked for indirect prompt injection (OWASP Agentic A04)
         * before they reach the LLM. On by default and fail-closed — disable with
         * {@code atmosphere.ai.rag.safety.enabled=false}.
         */
        public static class Safety {

            /** Master switch. Default {@code true} (protected out of the box). */
            private boolean enabled = true;

            /** Classifier tier: {@code RULE_BASED} (default), {@code EMBEDDING_SIMILARITY}, {@code LLM_CLASSIFIER}. */
            private String tier = "RULE_BASED";

            /** Breach policy: {@code DROP} (default), {@code FLAG}, {@code SANITIZE}. */
            private String onBreach = "DROP";

            /** Admit documents on classifier error. Default {@code false} (fail-closed). */
            private boolean failOpen;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getTier() {
                return tier;
            }

            public void setTier(String tier) {
                this.tier = tier;
            }

            public String getOnBreach() {
                return onBreach;
            }

            public void setOnBreach(String onBreach) {
                this.onBreach = onBreach;
            }

            public boolean isFailOpen() {
                return failOpen;
            }

            public void setFailOpen(boolean failOpen) {
                this.failOpen = failOpen;
            }
        }
    }

    /**
     * Long-term-memory configuration group, bound to {@code atmosphere.ai.memory.*}.
     * Currently carries the injection-safety screen
     * ({@code atmosphere.ai.memory.safety.*}).
     */
    public static class Memory {

        private MemorySafety safety = new MemorySafety();

        public MemorySafety getSafety() {
            return safety;
        }

        public void setSafety(MemorySafety safety) {
            this.safety = safety;
        }

        /**
         * Long-term-memory injection-safety screen, bound to
         * {@code atmosphere.ai.memory.safety.*}. Every fact extracted into a
         * {@code LongTermMemory} store is screened for indirect prompt injection
         * (OWASP Agentic A03 — Memory Poisoning) before it is persisted and later
         * re-injected into the system prompt. On by default and fail-closed —
         * disable with {@code atmosphere.ai.memory.safety.enabled=false}.
         */
        public static class MemorySafety {

            /** Master switch. Default {@code true} (protected out of the box). */
            private boolean enabled = true;

            /** Classifier tier: {@code RULE_BASED} (default), {@code EMBEDDING_SIMILARITY}, {@code LLM_CLASSIFIER}. */
            private String tier = "RULE_BASED";

            /** Breach policy: {@code DROP} (default), {@code FLAG}, {@code SANITIZE}. */
            private String onBreach = "DROP";

            /** Admit facts on classifier error. Default {@code false} (fail-closed). */
            private boolean failOpen;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getTier() {
                return tier;
            }

            public void setTier(String tier) {
                this.tier = tier;
            }

            public String getOnBreach() {
                return onBreach;
            }

            public void setOnBreach(String onBreach) {
                this.onBreach = onBreach;
            }

            public boolean isFailOpen() {
                return failOpen;
            }

            public void setFailOpen(boolean failOpen) {
                this.failOpen = failOpen;
            }
        }
    }

    /**
     * Configuration for opt-in model routing, bound to
     * {@code atmosphere.ai.routing.*}. Off by default. All four
     * {@code RoutingRule} families (content, model, cost, latency) are
     * config-driven; see {@code modules/ai/README.md} (§ Routing).
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
     * highest-{@link ModelOptionProperties#getCapability() capability} model from
     * {@link #models} whose total cost fits within {@link #maxCost}. A rule with
     * a {@code null} {@link #maxCost} or empty {@link #models} is skipped with a
     * {@code WARN} at startup.
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
     * highest-{@link ModelOptionProperties#getCapability() capability} model from
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
     * via {@link #toModelOption}. When both {@link #baseUrl} and {@link #apiKey}
     * are blank the framework-resolved client is reused (only the model name
     * changes); otherwise a dedicated OpenAI-compatible client is built.
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
         * {@code resolver} so {@code base-url}/{@code api-key} (or their absence)
         * are honored identically to content/model rules.
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
     * Harness preset, bound to {@code atmosphere.ai.harness.*}. The starter
     * bridges the preset switches into framework init-params
     * ({@code org.atmosphere.ai.harness.*}, {@code org.atmosphere.ai.compaction},
     * {@code org.atmosphere.ai.prompt-cache.default}) read by the AI endpoint
     * processors. The {@code enabled} switch is tri-state: unset (the default)
     * leaves the decision to each annotation's {@code harness()} attribute
     * ({@code @Agent} defaults to the full harness, {@code @AiEndpoint} stays
     * bare), {@code true} turns the full harness on for bare
     * {@code @AiEndpoint}s too and implies {@code atmosphere.durable-runs.enabled}
     * when the operator did not set that property explicitly (an explicit
     * {@code false} is honoured), and {@code false} is the operational kill
     * switch — harness features stay off everywhere, beating every annotation.
     * The preset never enables code execution — see {@link CodeProperties}.
     */
    public static class HarnessProperties {

        /**
         * Tri-state app-wide switch. {@code null} (the default) = unset — only
         * a non-null value is bridged to the framework, so an explicit
         * {@code false} reaches the runtime as the kill switch while an absent
         * property stays absent (annotation defaults apply).
         */
        private Boolean enabled;

        /** Endpoint paths excluded from the harness; beats annotations (only the kill switch is stronger). */
        private java.util.List<String> excludePaths = new java.util.ArrayList<>();

        /**
         * Conversation-memory compaction strategy: {@code sliding-window} or
         * {@code summarizing}. Unset = the framework default (sliding-window).
         */
        private String compaction;

        /**
         * Default prompt-cache policy seeded on endpoints whose annotation
         * declares none: {@code none}, {@code conservative}, or
         * {@code aggressive}. Unset = the framework default.
         */
        private String promptCacheDefault;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.List<String> getExcludePaths() {
            return excludePaths;
        }

        public void setExcludePaths(java.util.List<String> excludePaths) {
            this.excludePaths = excludePaths;
        }

        public String getCompaction() {
            return compaction;
        }

        public void setCompaction(String compaction) {
            this.compaction = compaction;
        }

        public String getPromptCacheDefault() {
            return promptCacheDefault;
        }

        public void setPromptCacheDefault(String promptCacheDefault) {
            this.promptCacheDefault = promptCacheDefault;
        }
    }

    /**
     * Code-exec (code-as-action) property bridge, bound to
     * {@code atmosphere.ai.code.*}. Each property that is set is bridged to its
     * {@code org.atmosphere.ai.code.*} JVM system property before the
     * Atmosphere framework initializes, so the sysprop-only code-exec keys
     * become reachable from {@code application.yml}. A system property the
     * operator already set on the JVM always wins (it is never overridden).
     * <strong>Off by default and never enabled by the harness preset</strong>
     * — executing model-generated code stays an explicit opt-in (Correctness
     * Invariant #6). Nullable wrapper types mean "unset = don't bridge, the
     * hardened framework defaults apply".
     */
    public static class CodeProperties {

        /** Master switch for the {@code code_exec} tool. Default {@code false} (default-deny). */
        private boolean enabled = false;

        /** Container engine: {@code auto} (framework default), {@code docker}, or {@code podman}. */
        private String engine;

        /** Container image providing the interpreters; framework default is the pinned Playwright image. */
        private String image;

        /** Container network mode. Framework default {@code none} (no network). */
        private String network;

        /** Memory cap in container-engine syntax (e.g. {@code 512m}). */
        private String memory;

        /** CPU cap (e.g. {@code 1.0}). */
        private Double cpus;

        /** Max processes inside the sandbox. */
        private Integer pidsLimit;

        /** Per-command wall-clock budget in seconds. */
        private Long execTimeoutSeconds;

        /** Max total sandbox lifetime in seconds before it is force-closed. */
        private Long sandboxTtlSeconds;

        /** Per-command stdout/stderr capture cap in bytes. */
        private Integer maxOutputBytes;

        /** Optional one-time bootstrap command run once when the sandbox starts. */
        private String setup;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEngine() {
            return engine;
        }

        public void setEngine(String engine) {
            this.engine = engine;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public String getMemory() {
            return memory;
        }

        public void setMemory(String memory) {
            this.memory = memory;
        }

        public Double getCpus() {
            return cpus;
        }

        public void setCpus(Double cpus) {
            this.cpus = cpus;
        }

        public Integer getPidsLimit() {
            return pidsLimit;
        }

        public void setPidsLimit(Integer pidsLimit) {
            this.pidsLimit = pidsLimit;
        }

        public Long getExecTimeoutSeconds() {
            return execTimeoutSeconds;
        }

        public void setExecTimeoutSeconds(Long execTimeoutSeconds) {
            this.execTimeoutSeconds = execTimeoutSeconds;
        }

        public Long getSandboxTtlSeconds() {
            return sandboxTtlSeconds;
        }

        public void setSandboxTtlSeconds(Long sandboxTtlSeconds) {
            this.sandboxTtlSeconds = sandboxTtlSeconds;
        }

        public Integer getMaxOutputBytes() {
            return maxOutputBytes;
        }

        public void setMaxOutputBytes(Integer maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
        }

        public String getSetup() {
            return setup;
        }

        public void setSetup(String setup) {
            this.setup = setup;
        }
    }

    /**
     * Durable agent-run execution. <strong>Off by default</strong> — turning it on
     * is the operator's explicit opt-in (Correctness Invariant #6). When enabled,
     * the default journal is the bundled crash-durable SQLite store; if the
     * checkpoint module that supplies it is absent the autoconfig falls back to an
     * in-memory journal and logs that the deployment is NOT crash-durable.
     */
    public static class DurableRunsProperties {

        /** Master switch. When false no run scope is ever installed (the default). */
        private boolean enabled = false;

        /** Journal backend: {@code sqlite} (default, crash-durable) or {@code memory}. */
        private String journal = "sqlite";

        /** SQLite database file path for the {@code sqlite} journal. */
        private String path = "${java.io.tmpdir}/atmosphere-runs.db";

        /** How long a single-writer run lease is held before a crash-recovery takeover. */
        private Duration leaseTtl = Duration.ofMinutes(5);

        /** Keep a run's effect history after it completes successfully (audit/inspection). */
        private boolean retainOnSuccess = false;

        /** Cap on concurrently retained runs (oldest terminal run evicted past it). */
        private int maxRuns = 10_000;

        /** Hard per-run effect cap; exceeding it fails the run rather than dropping effects. */
        private int maxEffectsPerRun = 2_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getJournal() {
            return journal;
        }

        public void setJournal(String journal) {
            this.journal = journal;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Duration getLeaseTtl() {
            return leaseTtl;
        }

        public void setLeaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
        }

        public boolean isRetainOnSuccess() {
            return retainOnSuccess;
        }

        public void setRetainOnSuccess(boolean retainOnSuccess) {
            this.retainOnSuccess = retainOnSuccess;
        }

        public int getMaxRuns() {
            return maxRuns;
        }

        public void setMaxRuns(int maxRuns) {
            this.maxRuns = maxRuns;
        }

        public int getMaxEffectsPerRun() {
            return maxEffectsPerRun;
        }

        public void setMaxEffectsPerRun(int maxEffectsPerRun) {
            this.maxEffectsPerRun = maxEffectsPerRun;
        }
    }

    /**
     * Session-tape configuration, bound to {@code atmosphere.ai.tape.*}. When
     * {@code enabled}, every AI streaming session crossing the endpoint or
     * pipeline dispatch path is recorded as an append-only per-run step log —
     * as-produced at the session boundary, post-decorator.
     */
    public static class TapeProperties {

        /** Master switch. When false no recorder is ever installed (the default). */
        private boolean enabled = false;

        /** Store backend: {@code sqlite} (default, crash-durable) or {@code memory}. */
        private String store = "sqlite";

        /** SQLite database file path for the {@code sqlite} store. */
        private String path = "${java.io.tmpdir}/atmosphere-tape.db";

        /** Cap on retained runs (oldest terminal run evicted past it). */
        private int maxRuns = 10_000;

        /** Per-run step cap; past it recording stops and the run is flagged truncated. */
        private int maxStepsPerRun = 5_000;

        /** Per-run text accumulator cap in characters before a forced TEXT-step flush. */
        private int maxTextChars = 262_144;

        /** Bounded step-queue capacity; overflow drops steps (never terminals). */
        private int queueCapacity = 8192;

        /** OPEN runs with no append for this long are marked ABANDONED. */
        private Duration idleTimeout = Duration.ofMinutes(30);

        /** Minimum age before the writer tick flushes accumulated text as a TEXT step. */
        private Duration textFlushInterval = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getMaxRuns() {
            return maxRuns;
        }

        public void setMaxRuns(int maxRuns) {
            this.maxRuns = maxRuns;
        }

        public int getMaxStepsPerRun() {
            return maxStepsPerRun;
        }

        public void setMaxStepsPerRun(int maxStepsPerRun) {
            this.maxStepsPerRun = maxStepsPerRun;
        }

        public int getMaxTextChars() {
            return maxTextChars;
        }

        public void setMaxTextChars(int maxTextChars) {
            this.maxTextChars = maxTextChars;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getTextFlushInterval() {
            return textFlushInterval;
        }

        public void setTextFlushInterval(Duration textFlushInterval) {
            this.textFlushInterval = textFlushInterval;
        }
    }
}
