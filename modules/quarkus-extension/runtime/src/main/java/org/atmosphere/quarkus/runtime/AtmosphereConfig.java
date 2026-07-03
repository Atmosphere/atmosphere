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
     * screens plus the deep-agent preset.
     */
    interface Ai {

        /**
         * RAG configuration block.
         *
         * @return the RAG sub-configuration block
         */
        Rag rag();

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
         * Deep-agent preset configuration block.
         *
         * @return the deep-agent sub-configuration block
         */
        DeepAgent deepAgent();

        /**
         * Deep-agent preset, bound to {@code quarkus.atmosphere.ai.deep-agent.*}.
         * One switch that enables Atmosphere's existing deep-agent primitives
         * (default-on conversation memory, long-term memory, subagent
         * delegation, prompt-cache seeding) instead of manual per-endpoint
         * wiring. The deployment processor bridges these keys to the
         * {@code org.atmosphere.ai.deep-agent.*} framework init-params read
         * once per framework by {@code AiEndpointProcessor} in
         * {@code atmosphere-ai}. Off by default — turning it on is the
         * operator's explicit opt-in (Correctness Invariant #6).
         */
        interface DeepAgent {

            /**
             * Master switch. Defaults to {@code false} (off); set {@code true}
             * to enable the deep-agent preset. On Quarkus an enabled preset
             * also implies the durable-run spine unless {@code durable-runs}
             * below is set to {@code false}.
             *
             * @return {@code true} if the deep-agent preset is enabled
             */
            @WithDefault("false")
            boolean enabled();

            /**
             * Endpoint paths excluded from the preset's default-on
             * conversation memory. Bridged (comma-joined) to the
             * {@code org.atmosphere.ai.deep-agent.exclude-paths} init-param.
             *
             * @return the excluded endpoint paths
             */
            Optional<List<String>> excludePaths();

            /**
             * Conversation-memory compaction strategy: {@code sliding-window}
             * (default) or {@code summarizing}. Bridged to the
             * {@code org.atmosphere.ai.compaction} init-param; honored whether
             * or not the preset is enabled.
             *
             * @return the compaction strategy name
             */
            Optional<String> compaction();

            /**
             * Default prompt-cache policy seeded on endpoints whose
             * {@code @AiEndpoint} {@code promptCache()} is {@code NONE}:
             * {@code none}, {@code conservative}, or {@code aggressive}.
             * Bridged to the {@code org.atmosphere.ai.prompt-cache.default}
             * init-param; honored whether or not the preset is enabled.
             *
             * @return the prompt-cache default policy name
             */
            Optional<String> promptCacheDefault();

            /**
             * Whether an enabled preset implies the durable-run spine (as if
             * {@code quarkus.atmosphere.durable-runs.enabled=true}). A
             * {@code @WithDefault} mapping cannot distinguish an unset
             * {@code durable-runs.enabled} from an explicit {@code false}, so
             * this key is the preset's explicit opt-out; it never blocks an
             * explicit {@code quarkus.atmosphere.durable-runs.enabled=true}.
             *
             * @return {@code true} if the preset should install the durable-run spine
             */
            @WithDefault("true")
            boolean durableRuns();
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
         * Master switch. Defaults to {@code false} (off); set {@code true} to
         * record and replay agent runs. The deep-agent preset
         * ({@code quarkus.atmosphere.ai.deep-agent.enabled=true}) also installs
         * the spine unless {@code quarkus.atmosphere.ai.deep-agent.durable-runs=false}.
         *
         * @return {@code true} if the durable-run spine should be installed
         */
        @WithDefault("false")
        boolean enabled();

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
