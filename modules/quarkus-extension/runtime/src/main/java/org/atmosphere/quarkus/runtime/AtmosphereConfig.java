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
package org.atmosphere.quarkus.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Atmosphere Framework configuration.
 */
@ConfigMapping(prefix = "quarkus.atmosphere")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface AtmosphereConfig {

    /**
     * The URL pattern for the Atmosphere servlet mapping.
     */
    @WithDefault("/atmosphere/*")
    String servletPath();

    /**
     * Comma-separated list of packages to scan for Atmosphere annotations.
     */
    Optional<String> packages();

    /**
     * The load-on-startup order for the Atmosphere servlet.
     * Must be greater than 0 for Quarkus to initialize the servlet at startup
     * (Quarkus skips {@code setLoadOnStartup} when the value is {@code <= 0}).
     */
    @WithDefault("1")
    int loadOnStartup();

    /**
     * Whether to enable HTTP session support.
     */
    @WithDefault("false")
    boolean sessionSupport();

    /**
     * The fully qualified class name of the Broadcaster implementation.
     */
    Optional<String> broadcasterClass();

    /**
     * The fully qualified class name of the BroadcasterCache implementation.
     */
    Optional<String> broadcasterCacheClass();

    /**
     * Whether to enable WebSocket support.
     */
    Optional<Boolean> websocketSupport();

    /**
     * The heartbeat interval. Accepts ISO-8601 duration or Quarkus shorthand
     * ({@code 30s}, {@code 5m}, {@code 1h}). Converted to seconds internally.
     */
    Optional<Duration> heartbeatInterval();

    /**
     * Additional Atmosphere init parameters passed to the servlet.
     */
    Map<String, String> initParams();

    /**
     * Optional subtitle shown by the bundled Atmosphere Console. When blank,
     * the {@code /api/console/info} servlet picks a mode-aware default
     * ({@code "Multi-client broadcast chat"} for {@code @ManagedService}
     * endpoints, {@code "Runtime: <name>"} otherwise).
     */
    Optional<String> consoleSubtitle();

    /**
     * Optional explicit endpoint the bundled Atmosphere Console connects to.
     * When blank, the {@code /api/console/info} servlet auto-detects via the
     * registered handler map (prefer {@code /atmosphere/agent/*} over generic
     * paths, fall back to the first {@code /atmosphere/*} that is not
     * {@code /atmosphere/admin/*}).
     */
    Optional<String> consoleEndpoint();

    /**
     * Optional wire transport the bundled Atmosphere Console should speak to
     * reach this application's endpoint: {@code atmosphere} (default —
     * WebSocket + long-polling over the Atmosphere protocol), {@code grpc},
     * {@code a2a} or {@code ag-ui}. Unrecognized values are reported as
     * {@code atmosphere} by {@code /api/console/info}. Mirrors the Spring
     * starter's {@code atmosphere.console-transport}.
     */
    Optional<String> consoleTransport();

    /**
     * Whether to enable the bounded-memory {@link org.atmosphere.cache.BoundedMemoryCache}
     * and {@link org.atmosphere.interceptor.MessageAckInterceptor}. Mirrors the Spring
     * Boot starter's {@code atmosphere.cache.enabled} property; when {@code true} the
     * deployment processor registers {@link org.atmosphere.cache.BoundedMemoryCache} as
     * the default broadcaster cache via the {@code broadcaster-cache-class} init param.
     */
    @WithDefault("false")
    boolean cacheEnabled();

    /**
     * WebTransport over HTTP/3 configuration.
     *
     * @return the WebTransport sub-configuration block
     */
    WebTransport webTransport();

    /**
     * WebTransport over HTTP/3 configuration block. Mirrors the Spring Boot
     * starter's {@code atmosphere.web-transport.*} keys.
     */
    interface WebTransport {

        /**
         * Whether to start the Netty HTTP/3 sidecar on application startup.
         * Defaults to {@code false}; users opting in must also pull in the
         * {@code atmosphere-webtransport-reactor-netty} module.
         *
         * @return {@code true} if the sidecar should be started
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * UDP port for the HTTP/3 sidecar. Default {@code 4443}.
         *
         * @return the configured UDP port
         */
        @WithDefault("4443")
        int port();
    }

    /**
     * AI configuration block. Mirrors the Spring Boot starter's
     * {@code atmosphere.ai.*} keys.
     *
     * @return the AI sub-configuration block
     */
    Ai ai();

    /**
     * AI sub-configuration: the RAG and long-term-memory injection-safety
     * screens plus the agent-harness preset.
     */
    interface Ai {

        /**
         * RAG configuration block.
         *
         * @return the RAG sub-configuration block
         */
        Rag rag();

        /**
         * Voice-mode endpoint configuration block.
         *
         * @return the voice sub-configuration block
         */
        Voice voice();

        /**
         * Voice-mode endpoint, bound to {@code quarkus.atmosphere.ai.voice.*}.
         * When enabled, {@code VoiceEndpointHandler} is mounted on
         * {@link #path()} and drives the speech-to-speech loop against the
         * {@code RealtimeVoiceProvider} resolved per connect. Off by default —
         * opening realtime provider sessions is the operator's explicit
         * opt-in (Correctness Invariant #6).
         */
        interface Voice {

            /**
             * Master switch for the voice endpoint.
             *
             * @return whether the voice endpoint is mounted
             */
            @WithDefault("false")
            boolean enabled();

            /**
             * Mount path of the voice endpoint.
             *
             * @return the Atmosphere handler path
             */
            @WithDefault("/atmosphere/voice")
            String path();

            /**
             * Realtime model id (provider-specific); empty for the provider
             * default.
             *
             * @return the model id
             */
            java.util.Optional<String> model();

            /**
             * Synthesized voice name; empty for the provider default.
             *
             * @return the voice name
             */
            java.util.Optional<String> voiceName();

            /**
             * Assistant instructions for the session.
             *
             * @return the system prompt
             */
            java.util.Optional<String> systemPrompt();

            /**
             * MIME type of the audio the client sends; empty for the
             * framework default ({@code audio/pcm;rate=16000}).
             *
             * @return the input MIME type
             */
            java.util.Optional<String> inputMime();

            /**
             * MIME type of the audio the provider returns; empty for the
             * framework default ({@code audio/pcm;rate=24000}).
             *
             * @return the output MIME type
             */
            java.util.Optional<String> outputMime();
        }

        /**
         * RAG sub-configuration. Currently carries the injection-safety screen.
         */
        interface Rag {

            /**
             * RAG injection-safety screen.
             *
             * @return the safety sub-configuration block
             */
            Safety safety();

            /**
             * RAG injection-safety screen, bound to
             * {@code quarkus.atmosphere.ai.rag.safety.*}. Every
             * {@code @AiEndpoint} {@code ContextProvider} is wrapped so retrieved
             * documents are checked for indirect prompt injection (OWASP Agentic
             * A04) before they reach the LLM. On by default and fail-closed.
             */
            interface Safety {

                /**
                 * Master switch. Defaults to {@code true} (protected out of the
                 * box); set {@code false} to disable the screen.
                 *
                 * @return {@code true} if retrieved documents should be screened
                 */
                @WithDefault("true")
                boolean enabled();

                /**
                 * Classifier tier: {@code RULE_BASED} (default, zero-dependency),
                 * {@code EMBEDDING_SIMILARITY}, or {@code LLM_CLASSIFIER}. Higher
                 * tiers downgrade to {@code RULE_BASED} when their runtime is absent.
                 *
                 * @return the classifier tier name
                 */
                @WithDefault("RULE_BASED")
                String tier();

                /**
                 * Breach policy for a flagged document: {@code DROP} (default),
                 * {@code FLAG}, or {@code SANITIZE}.
                 *
                 * @return the breach policy name
                 */
                @WithDefault("DROP")
                String onBreach();

                /**
                 * Admit documents when the classifier errors. Defaults to
                 * {@code false} (fail-closed).
                 *
                 * @return {@code true} to admit on classifier error
                 */
                @WithDefault("false")
                boolean failOpen();
            }
        }

        /**
         * Long-term-memory configuration block.
         *
         * @return the memory sub-configuration block
         */
        Memory memory();

        /**
         * Governance-plane configuration block.
         *
         * @return the governance sub-configuration block
         */
        Governance governance();

        /**
         * Guardrail configuration block.
         *
         * @return the guardrails sub-configuration block
         */
        Guardrails guardrails();

        /**
         * Outbound-LLM gateway configuration block.
         *
         * @return the gateway sub-configuration block
         */
        Gateway gateway();

        /**
         * Outbound gateway sub-configuration, bound to
         * {@code quarkus.atmosphere.ai.gateway.*}. Mirrors the Spring Boot
         * starter's {@code atmosphere.ai.gateway.*} keys.
         *
         * <p>The framework default is the permissive dev gateway (1M
         * calls/hour, effectively unrestricted). Setting
         * {@code profile=production} installs the
         * {@code GatewayProfiles.production()} limits into the process-wide
         * {@code AiGatewayHolder}: a per-principal request ceiling plus a
         * separate, tighter ceiling for the shared {@code anonymous} bucket
         * every unauthenticated caller collapses into.</p>
         */
        interface Gateway {

            /**
             * Named gateway profile. {@code default} (the default value)
             * leaves the permissive dev gateway in place; {@code production}
             * installs the enforcing profile.
             *
             * @return the profile name
             */
            @WithDefault("default")
            String profile();

            /**
             * Per-principal request ceiling inside one window. {@code 0}
             * (the default) uses the production profile's built-in value.
             *
             * @return the per-principal request ceiling
             */
            @WithDefault("0")
            int maxRequestsPerWindow();

            /**
             * Sliding-window length in seconds. {@code 0} (the default) uses
             * the production profile's built-in value.
             *
             * @return the window length in seconds
             */
            @WithDefault("0")
            int windowSeconds();

            /**
             * Ceiling for the shared {@code anonymous} bucket. {@code 0}
             * (the default) derives it from the per-principal ceiling using
             * the production profile's anonymous divisor.
             *
             * @return the anonymous-bucket request ceiling
             */
            @WithDefault("0")
            int anonymousMaxRequests();
        }

        /**
         * Guardrail sub-configuration, bound to
         * {@code quarkus.atmosphere.ai.guardrails.*}. Mirrors the Spring Boot
         * starter's {@code atmosphere.ai.guardrails.*} keys; currently carries
         * the per-tenant cost ceiling.
         */
        interface Guardrails {

            /**
             * Per-tenant cost-ceiling configuration.
             *
             * @return the cost sub-configuration block
             */
            Cost cost();

            /**
             * Opt-in per-tenant cost ceiling, bound to
             * {@code quarkus.atmosphere.ai.guardrails.cost.*}. Off by default.
             * When {@code enabled}, {@code AtmosphereCostAccountantProducer}
             * constructs a {@code CostCeilingGuardrail} with {@code budget-usd}
             * and bridges it into the framework guardrail chain; when a
             * {@code TokenPricing} CDI bean is also present, the built-in
             * {@code CostCeilingAccountant} is installed so per-turn
             * {@code TokenUsage} accrues dollar spend against the ceiling —
             * the same observability-to-enforcement loop the Spring Boot
             * starter wires from {@code atmosphere.ai.guardrails.cost.*}.
             */
            interface Cost {

                /**
                 * Master switch. Defaults to {@code false}; set {@code true}
                 * to construct the built-in {@code CostCeilingGuardrail}. A
                 * user-supplied {@code CostCeilingGuardrail} CDI bean always
                 * wins over this key.
                 *
                 * @return {@code true} if the built-in cost ceiling is enabled
                 */
                @WithDefault("false")
                boolean enabled();

                /**
                 * Per-tenant ceiling in USD. {@code 0} (the default) keeps
                 * the guardrail observational — spend accrues but nothing
                 * blocks — mirroring the Spring Boot starter's
                 * {@code atmosphere.ai.guardrails.cost.budget-usd} default.
                 *
                 * @return the per-tenant budget in USD
                 */
                @WithDefault("0.0")
                double budgetUsd();
            }
        }

        /**
         * Long-term-memory sub-configuration. Currently carries the
         * injection-safety screen for the memory write path.
         */
        interface Memory {

            /**
             * Long-term-memory injection-safety screen.
             *
             * @return the safety sub-configuration block
             */
            Safety safety();

            /**
             * Long-term-memory injection-safety screen, bound to
             * {@code quarkus.atmosphere.ai.memory.safety.*}. Every fact extracted
             * into a {@code LongTermMemory} store is screened for indirect prompt
             * injection (OWASP Agentic A03 — Memory Poisoning) before it is
             * persisted and later re-injected. On by default and fail-closed.
             */
            interface Safety {

                /**
                 * Master switch. Defaults to {@code true} (protected out of the
                 * box); set {@code false} to disable the screen.
                 *
                 * @return {@code true} if extracted facts should be screened
                 */
                @WithDefault("true")
                boolean enabled();

                /**
                 * Classifier tier: {@code RULE_BASED} (default, zero-dependency),
                 * {@code EMBEDDING_SIMILARITY}, or {@code LLM_CLASSIFIER}. Higher
                 * tiers downgrade to {@code RULE_BASED} when their runtime is absent.
                 *
                 * @return the classifier tier name
                 */
                @WithDefault("RULE_BASED")
                String tier();

                /**
                 * Breach policy for a flagged fact: {@code DROP} (default),
                 * {@code FLAG}, or {@code SANITIZE}.
                 *
                 * @return the breach policy name
                 */
                @WithDefault("DROP")
                String onBreach();

                /**
                 * Admit facts when the classifier errors. Defaults to
                 * {@code false} (fail-closed).
                 *
                 * @return {@code true} to admit on classifier error
                 */
                @WithDefault("false")
                boolean failOpen();
            }
        }

        /**
         * Governance-plane sub-configuration. Currently carries the durable
         * governance-feedback settings.
         */
        interface Governance {

            /**
             * Durable governance-feedback settings.
             *
             * @return the durable-memory sub-configuration block
             */
            Memory memory();

            /**
             * Durable governance feedback, bound to
             * {@code quarkus.atmosphere.ai.governance.memory.*}. Opt-in (off by default):
             * when on, deny/prefer decisions are persisted to the resolved
             * {@code LongTermMemory} (provenance-tagged) so the feedback interceptor recalls
             * them across sessions and restarts. The deployment processor bridges these keys
             * to the {@code org.atmosphere.ai.governance.memory.*} framework init-params read
             * once per framework by {@code AiEndpointProcessor} in {@code atmosphere-ai}.
             */
            interface Memory {

                /**
                 * Master switch for durable recall. Defaults to {@code false}
                 * (ephemeral loop only, no persistence).
                 *
                 * @return {@code true} to persist governance guidance durably
                 */
                @WithDefault("false")
                boolean enabled();

                /**
                 * Lesson time-to-live in seconds; {@code 0} (default) means no expiry.
                 *
                 * @return the lesson TTL in seconds
                 */
                @WithDefault("0")
                long ttlSeconds();

                /**
                 * Confidence stamped on each persisted lesson (0.0–1.0). Default {@code 1.0}.
                 *
                 * @return the stamped confidence
                 */
                @WithDefault("1.0")
                double confidence();

                /**
                 * Read-gate floor: lessons below this confidence are dropped on read.
                 * Default {@code 0.0}.
                 *
                 * @return the minimum confidence to recall a lesson
                 */
                @WithDefault("0.0")
                double minConfidence();
            }
        }

        /**
         * Agent-harness preset configuration block.
         *
         * @return the harness sub-configuration block
         */
        Harness harness();

        /**
         * Agent-harness preset, bound to {@code quarkus.atmosphere.ai.harness.*}.
         * Governs Atmosphere's deep-agent primitives (default-on conversation
         * memory, long-term memory, subagent delegation, prompt-cache seeding,
         * the plan surface, the conversation-scoped file workspace)
         * app-wide instead of manual per-endpoint wiring. The deployment
         * processor bridges these keys to the
         * {@code org.atmosphere.ai.harness.*} framework init-params read
         * once per framework by {@code AiEndpointProcessor} in
         * {@code atmosphere-ai}.
         */
        interface Harness {

            /**
             * Tri-state app-wide switch. Unset (the default) leaves the
             * decision to each annotation's {@code harness()} attribute;
             * {@code true} turns the full harness on for every
             * {@code @AiEndpoint} whose annotation stays bare; {@code false}
             * is the kill switch — harness features stay off everywhere,
             * beating every annotation. On Quarkus an explicit {@code true}
             * also implies the durable-run spine unless {@code durable-runs}
             * below is set to {@code false}.
             *
             * <p>This config root is {@code BUILD_AND_RUN_TIME_FIXED} (the
             * value is baked into servlet init-params by a build step), so
             * flipping the switch — including the {@code false} kill switch —
             * requires a <b>rebuild</b>, not just a restart: Quarkus ignores
             * runtime overrides of build-time-fixed properties. On Spring
             * Boot and plain servlet the flag is read at startup.</p>
             *
             * @return the explicit app-wide switch, empty when unset
             */
            Optional<Boolean> enabled();

            /**
             * Endpoint paths the harness skips entirely — no feature from the
             * annotation's {@code harness()} or the app-wide switch applies
             * to them. Bridged (comma-joined) to the
             * {@code org.atmosphere.ai.harness.exclude-paths} init-param.
             *
             * @return the excluded endpoint paths
             */
            Optional<List<String>> excludePaths();

            /**
             * Conversation-memory compaction strategy: {@code sliding-window}
             * (default) or {@code summarizing}. Bridged to the
             * {@code org.atmosphere.ai.compaction} init-param; honored whatever
             * the app-wide switch says.
             *
             * @return the compaction strategy name
             */
            Optional<String> compaction();

            /**
             * Default prompt-cache policy seeded on endpoints whose
             * {@code @AiEndpoint} {@code promptCache()} is {@code NONE}:
             * {@code none}, {@code conservative}, or {@code aggressive}.
             * Bridged to the {@code org.atmosphere.ai.prompt-cache.default}
             * init-param; honored whatever the app-wide switch says.
             *
             * @return the prompt-cache default policy name
             */
            Optional<String> promptCacheDefault();

            /**
             * Whether an explicitly enabled harness ({@code enabled=true})
             * implies the durable-run spine (as if
             * {@code quarkus.atmosphere.durable-runs.enabled=true}). The
             * implication applies only while {@code durable-runs.enabled}
             * stays unset: an explicit {@code durable-runs.enabled} value —
             * {@code true} or {@code false} — always wins over both this key
             * and the implication.
             *
             * @return {@code true} if the enabled harness should install the durable-run spine
             */
            @WithDefault("true")
            boolean durableRuns();
        }

        /**
         * Session-tape configuration block.
         *
         * @return the tape sub-configuration block
         */
        Tape tape();

        /**
         * Durable-run checkpoint-store configuration block.
         *
         * @return the checkpoint sub-configuration block
         */
        Checkpoint checkpoint();

        /**
         * Session-tape sub-configuration, bound to
         * {@code quarkus.atmosphere.ai.tape.*}. Mirrors the Spring Boot
         * starters' {@code atmosphere.ai.tape.*} keys; when {@code enabled}
         * the {@code AtmosphereTapeProducer} installs a {@code TapeRecorder}
         * so every AI streaming session crossing the endpoint or pipeline
         * dispatch path is recorded as an append-only per-run step log —
         * as-produced at the session boundary, post-decorator. Off by default
         * — turning it on is the operator's explicit opt-in (Correctness
         * Invariant #6). Requires {@code atmosphere-ai} on the classpath;
         * {@code store=sqlite} additionally requires
         * {@code atmosphere-checkpoint} for crash survival.
         */
        interface Tape {

            /**
             * Tri-state master switch, off when unset. Set {@code true} to
             * record every AI streaming session; no preset implies the tape,
             * so unset and an explicit {@code false} behave identically —
             * the shape mirrors {@code durable-runs.enabled} so the config
             * surfaces read the same way across both features.
             *
             * @return the explicit switch, empty when unset
             */
            Optional<Boolean> enabled();

            /**
             * Store backend: {@code sqlite} (crash-durable, bundled in
             * {@code atmosphere-checkpoint}) or {@code memory}. Defaults to
             * {@code sqlite}; falls back to the in-memory store with a
             * NOT-crash-durable warning when {@code atmosphere-checkpoint}
             * is absent (Correctness Invariant #5).
             *
             * @return the store backend name
             */
            @WithDefault("sqlite")
            String store();

            /**
             * Filesystem path for the SQLite tape store. The literal
             * {@code ${java.io.tmpdir}} is expanded to the JVM temp
             * directory. Ignored when {@code store=memory}.
             *
             * @return the SQLite database path
             */
            @WithDefault("${java.io.tmpdir}/atmosphere-tape.db")
            String path();

            /**
             * Maximum retained tape runs before the oldest terminal runs are
             * evicted. Bounds store growth (Correctness Invariant #3).
             *
             * @return the maximum retained run count
             */
            @WithDefault("10000")
            int maxRuns();

            /**
             * Per-run step cap; past it recording stops and the run is
             * flagged truncated — the stream itself is never failed. Bounds
             * per-run store growth (Correctness Invariant #3).
             *
             * @return the maximum steps retained per run
             */
            @WithDefault("5000")
            int maxStepsPerRun();

            /**
             * Per-run text accumulator cap in characters before a forced
             * TEXT-step flush.
             *
             * @return the text accumulator cap
             */
            @WithDefault("262144")
            int maxTextChars();

            /**
             * Bounded step-queue capacity between recording sessions and the
             * tape writer; overflow drops steps (never terminals) and counts
             * them (Correctness Invariant #3).
             *
             * @return the step-queue capacity
             */
            @WithDefault("8192")
            int queueCapacity();

            /**
             * OPEN runs with no append for this long are marked ABANDONED.
             * Accepts ISO-8601 duration or Quarkus shorthand ({@code 30s},
             * {@code 5m}, {@code 1h}). Defaults to {@code 30m}.
             *
             * @return the idle timeout
             */
            @WithDefault("30m")
            Duration idleTimeout();

            /**
             * Minimum age before the writer tick flushes accumulated text as
             * a TEXT step. Defaults to {@code 10s}.
             *
             * @return the text flush interval
             */
            @WithDefault("10s")
            Duration textFlushInterval();
        }

        /**
         * Durable-run checkpoint-store sub-configuration, bound to
         * {@code quarkus.atmosphere.ai.checkpoint.*}. Quarkus parity for the
         * Spring Boot starter's {@code AtmosphereCheckpointAutoConfiguration}:
         * a {@code CheckpointStore} CDI bean is produced out of the box so the
         * durable-run read plane ({@code /api/admin/checkpoints}) and the
         * console's Checkpoints tab work without hand-wiring. The default is a
         * bounded in-memory store (lost on restart, with a startup WARN);
         * {@code store=sqlite} upgrades to the crash-durable
         * {@code SqliteCheckpointStore} when {@code atmosphere-checkpoint} and
         * the SQLite JDBC driver are present. A user-supplied
         * {@code CheckpointStore} CDI bean (in-memory, SQLite, or Postgres via
         * {@code atmosphere-checkpoint-postgres}) always wins over this default
         * — the producer is a {@code @DefaultBean}, mirroring Spring's
         * {@code @ConditionalOnMissingBean} selection (Invariant #7).
         */
        interface Checkpoint {

            /**
             * Store backend: {@code memory} (default — bounded in-memory, lost
             * on restart) or {@code sqlite} (crash-durable, bundled in
             * {@code atmosphere-checkpoint}). {@code sqlite} falls back to the
             * in-memory store with a NOT-crash-durable warning when the SQLite
             * JDBC driver is absent (Correctness Invariant #5). Any user-supplied
             * {@code CheckpointStore} CDI bean overrides this key entirely.
             *
             * @return the store backend name
             */
            @WithDefault("memory")
            String store();

            /**
             * Filesystem path for the SQLite checkpoint store. The literal
             * {@code ${java.io.tmpdir}} is expanded to the JVM temp directory.
             * Ignored when {@code store=memory}.
             *
             * @return the SQLite database path
             */
            @WithDefault("${java.io.tmpdir}/atmosphere-checkpoints.db")
            String path();

            /**
             * Maximum retained snapshots before the oldest are evicted, applied
             * to both the in-memory and SQLite backends. Bounds store growth so
             * wire-driven or workflow-driven saves cannot grow it without bound
             * (Correctness Invariant #3). Defaults to {@code 10000}.
             *
             * @return the maximum retained snapshot count
             */
            @WithDefault("10000")
            int maxSnapshots();
        }
    }

    /**
     * Durable sessions configuration.
     *
     * @return the durable-sessions sub-configuration block
     */
    DurableSessions durableSessions();

    /**
     * Durable sessions sub-configuration. Mirrors the Spring Boot starter's
     * {@code atmosphere.durable-sessions.*} keys; the actual runtime
     * behaviour is exercised by {@link org.atmosphere.session.DurableSessionInterceptor}
     * and the SPI's pluggable {@code SessionStore} CDI beans.
     */
    interface DurableSessions {

        /**
         * Whether to install the {@link org.atmosphere.session.DurableSessionInterceptor}
         * on startup. Defaults to {@code false}; users opting in pick up the
         * default {@code InMemorySessionStore} unless they ship a pluggable
         * {@code SessionStore} CDI bean.
         *
         * @return {@code true} if the interceptor should be installed
         */
        @WithDefault("false")
        boolean enabled();
    }

    /**
     * Durable agent-runs configuration.
     *
     * @return the durable-runs sub-configuration block
     */
    DurableRuns durableRuns();

    /**
     * Durable agent-runs sub-configuration. Mirrors the Spring Boot starter's
     * {@code atmosphere.durable-runs.*} keys; when {@code enabled} the deployment
     * processor registers {@code AtmosphereDurableRunsProducer}, which installs the
     * effect-journal-backed {@code DurableRunSpine} so committed LLM rounds and tool
     * calls replay deterministically after a crash. Off by default — turning it on
     * is the operator's explicit opt-in (Correctness Invariant #6). Requires
     * {@code atmosphere-ai} on the classpath; {@code journal=sqlite} additionally
     * requires {@code atmosphere-checkpoint} for crash survival.
     */
    interface DurableRuns {

        /**
         * Tri-state master switch, off when unset. Set {@code true} to record
         * and replay agent runs. While unset, the agent-harness preset
         * ({@code quarkus.atmosphere.ai.harness.enabled=true}) also installs
         * the spine unless {@code quarkus.atmosphere.ai.harness.durable-runs=false}.
         * An explicit value always wins: {@code false} keeps the spine out even
         * when the harness implies it — mirroring the Spring starter, where the
         * operator's explicit opt-out survives the preset (Invariant #7).
         *
         * @return the explicit switch, empty when unset
         */
        Optional<Boolean> enabled();

        /**
         * Journal backend: {@code sqlite} (crash-durable, bundled in
         * {@code atmosphere-checkpoint}) or {@code memory} (same-process
         * idempotency only). Defaults to {@code sqlite}; falls back to the
         * in-memory journal with a NOT-crash-durable warning when
         * {@code atmosphere-checkpoint} is absent (Correctness Invariant #5).
         *
         * @return the journal backend name
         */
        @WithDefault("sqlite")
        String journal();

        /**
         * Filesystem path for the SQLite journal. The literal
         * {@code ${java.io.tmpdir}} is expanded to the JVM temp directory.
         * Ignored when {@code journal=memory}.
         *
         * @return the SQLite database path
         */
        @WithDefault("${java.io.tmpdir}/atmosphere-runs.db")
        String path();

        /**
         * Single-writer lease TTL. A crashed run's lease expires after this
         * duration, allowing a reconnect or admin re-drive to claim it. Accepts
         * ISO-8601 duration or Quarkus shorthand ({@code 30s}, {@code 5m},
         * {@code 1h}). Defaults to {@code 5m}.
         *
         * @return the lease time-to-live
         */
        @WithDefault("5m")
        Duration leaseTtl();

        /**
         * Whether to keep the effect history of runs that completed successfully
         * (for audit). Defaults to {@code false} — successful runs are pruned to
         * bound journal growth (Correctness Invariant #3).
         *
         * @return {@code true} to retain successful-run history
         */
        @WithDefault("false")
        boolean retainOnSuccess();

        /**
         * Maximum number of distinct runs retained in the journal before the
         * oldest are evicted. Bounds journal growth (Correctness Invariant #3).
         * Defaults to {@code 10000}.
         *
         * @return the maximum retained run count
         */
        @WithDefault("10000")
        int maxRuns();

        /**
         * Maximum number of effects retained per run. Bounds per-run journal
         * growth (Correctness Invariant #3). Defaults to {@code 2000}.
         *
         * @return the maximum effects retained per run
         */
        @WithDefault("2000")
        int maxEffectsPerRun();
    }
}
