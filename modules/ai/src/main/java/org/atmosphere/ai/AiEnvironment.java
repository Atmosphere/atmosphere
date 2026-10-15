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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.ai.llm.LogprobsMode;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.ai.llm.PromptCacheKeyMode;
import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The AI configuration of one application: its {@code atmosphere.ai.*} knob
 * values, its LLM settings, its per-agent settings and its own
 * {@link AgentRuntime} and {@link EmbeddingRuntime} instances. Each
 * {@link org.atmosphere.cpr.AtmosphereFramework} owns at most one, stored in
 * its {@link AtmosphereConfig#properties()} under {@link #PROPERTY}, so two
 * applications in one JVM (two Spring contexts, two embedded servers) never
 * read each other's settings, knobs or runtime clients.
 *
 * <h3>Where an environment comes from</h3>
 * <ul>
 *   <li>A host creates it with {@link #create(AtmosphereConfig, Function)}
 *       from its own configuration (the Spring Boot starters from the
 *       {@code Environment}, the Quarkus extension from
 *       {@code application.properties}) and closes it when the application
 *       stops.</li>
 *   <li>On a plain servlet or embedded server, {@link #of(AtmosphereConfig)}
 *       creates it the first time an {@code @AiEndpoint}, {@code @Agent} or
 *       {@code @Coordinator} registers, reading the knobs from the framework's
 *       init-params, and the framework's destroy closes it.</li>
 * </ul>
 *
 * <h3>Knobs</h3>
 * {@link #knob(String)} resolves, first non-blank wins: the JVM system
 * property, the knob's {@code LLM_*} environment variable, then this
 * environment's own values. System properties and environment variables are
 * JVM-wide by nature: they override every application's value. An environment
 * created lazily from init-params also reads a host configuration installed
 * with {@link AiConfig#installHostConfig} (not one belonging to another
 * environment) for a knob its init-params leave unset.
 *
 * <h3>Settings</h3>
 * An environment with no settings of its own ({@link #configure},
 * {@link #installClient}, {@link #adopt}) reads the process default's, so an
 * application that calls {@code AiConfig.configure} in {@code main()} and then
 * starts its server sees exactly those settings.
 *
 * <h3>The static API</h3>
 * {@link AiConfig} and {@link AgentRuntimeResolver} statics read and write
 * {@link #current()}: the environment bound to the calling thread (a
 * {@code @Prompt} method's thread is bound to its application's), else the
 * only open environment, else {@link #processDefault()}. Framework code holds
 * an explicit environment instead; the statics are for code that runs with no
 * framework and for user code.
 *
 * <h3>Native clients</h3>
 * A runtime adapter's instance of this application uses, first found: the
 * native client {@linkplain #bindClient bound} to this environment (the adapters'
 * typed binders that take an {@code AiEnvironment}, such as
 * {@code SpringAiAgentRuntime.setChatClient(AiEnvironment, ChatClient)}), then
 * the one the adapter's static binder holds ({@code SpringAiAgentRuntime.setChatClient(ChatClient)},
 * which applies to every application in the JVM), then the one this
 * application's auto-configuration {@linkplain #offerClient offered}. The Spring
 * Boot auto-configurations of the adapters bind or offer their context's beans
 * to the context's environment, so two Spring contexts with different
 * {@code ChatClient} beans each dispatch through their own.
 */
public final class AiEnvironment implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AiEnvironment.class);
    /** The messages the static API logged before environments keep their logger. */
    private static final Logger configLogger = LoggerFactory.getLogger(AiConfig.class);

    /**
     * The key under which a framework's environment is stored in
     * {@link AtmosphereConfig#properties()}.
     */
    public static final String PROPERTY = "org.atmosphere.ai.AiEnvironment";

    private enum Kind {
        /** The process-wide settings of the static API; never closed. */
        PROCESS_DEFAULT,
        /** Created by a host from its configuration. */
        HOST,
        /** Created from a plain framework's init-params on first use. */
        LAZY
    }

    private static final AtomicLong IDS = new AtomicLong();
    private static final ReentrantLock LIVE_LOCK = new ReentrantLock();
    /** The open environments, oldest first; replaced whole under {@link #LIVE_LOCK}. */
    private static volatile AiEnvironment[] live = new AiEnvironment[0];
    /** Plain, not inheritable: a pooled thread created while bound must not keep it. */
    private static final ThreadLocal<AiEnvironment> BOUND = new ThreadLocal<>();
    private static final AtomicBoolean AMBIGUITY_WARNED = new AtomicBoolean();
    private static final AiEnvironment PROCESS_DEFAULT =
            new AiEnvironment(Kind.PROCESS_DEFAULT, "process-default", Map.of());

    private final Kind kind;
    private final String name;
    private final Map<String, String> values;
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * This environment's own settings; the process default keeps its own in
     * the {@link AiConfig} static fields the static API always used.
     */
    private volatile AiConfig.LlmSettings ownSettings;
    /**
     * The generation values {@link #ownSettings} were built with that came
     * from the caller rather than from the knobs, so {@link #knobsNotInEffect}
     * does not report them. Written together with {@link #ownSettings}, under
     * {@link #lock}.
     */
    private volatile GenerationParams ownPinned = GenerationParams.defaults();
    /** Per-agent settings; bounded by the number of agents of this application. */
    private final ConcurrentHashMap<String, AiConfig.LlmSettings> agentSettings = new ConcurrentHashMap<>();
    /**
     * The settings this environment built itself and made its own, so
     * {@link #close()} shuts down their client while they are still its own;
     * written with {@link #ownSettings}, under {@link #lock}.
     */
    private volatile AiConfig.LlmSettings builtSettings;
    /** The agent settings {@link #configureForAgent} built, by agent; bounded like {@link #agentSettings}. */
    private final ConcurrentHashMap<String, AiConfig.LlmSettings> builtAgentSettings = new ConcurrentHashMap<>();
    private final AgentRuntimeRegistry runtimes;
    private final EmbeddingRuntimeRegistry embeddings;

    /** One native-client slot: the runtime adapter and the client type it takes. */
    private record BindingKey(Class<?> adapter, Class<?> type) {
    }

    /**
     * Native clients bound ({@link #bindClient}) and offered ({@link #offerClient}) for
     * this application; bounded by the slots of the runtime adapters on the
     * classpath (one or two per adapter).
     */
    private final ConcurrentHashMap<BindingKey, Object> boundClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BindingKey, Object> offeredClients = new ConcurrentHashMap<>();
    /** Whether this application explicitly bound a native client ({@link #markExplicitClientBinding()}). */
    private volatile boolean explicitBinding;
    /** The workspace whose {@code RUNTIME.md} seeded this environment's settings, if any. */
    private final AtomicReference<String> settingsOwner = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile AtmosphereConfig attachedTo;
    /** This environment's values on the legacy host-configuration stack. */
    private volatile AiConfig.HostConfig pushed;

    private AiEnvironment(Kind kind, String name, Map<String, String> values) {
        this.kind = kind;
        this.name = name;
        this.values = values;
        // The process default's runtimes are not attached to it: such an
        // instance keeps reading the static API, as every instance did before
        // environments existed.
        var attach = kind != Kind.PROCESS_DEFAULT;
        this.runtimes = new AgentRuntimeRegistry(this, attach);
        this.embeddings = new EmbeddingRuntimeRegistry(this, attach);
    }

    // -- Lookup and lifecycle --

    /**
     * The process-wide environment the static API falls back to: the settings
     * {@code AiConfig.configure} builds when no application is running, the
     * host configurations installed with {@link AiConfig#installHostConfig},
     * and the runtimes {@link AgentRuntimeResolver} resolves then. It is never
     * closed.
     *
     * @return the process default, never {@code null}
     */
    public static AiEnvironment processDefault() {
        return PROCESS_DEFAULT;
    }

    /**
     * Create an open environment whose knob values are read from
     * {@code lookup} now, once per knob property ({@link AiConfig#knobProperties()});
     * later changes to the host's configuration are not seen. The caller owns
     * it and closes it when its application stops.
     *
     * <p>While it is open, its values are also the host configuration the
     * process default reads (as {@link AiConfig#installHostConfig} installs
     * one), so settings a static {@code AiConfig.configure} builds while the
     * application starts carry the starting application's knobs.</p>
     *
     * @param lookup the host's configuration by property name; returns
     *               {@code null} for an unset property
     * @return the new environment
     */
    public static AiEnvironment create(Function<String, String> lookup) {
        return open(Kind.HOST, lookup);
    }

    /**
     * {@link #create(Function)}, then {@link #attachTo(AtmosphereConfig)}.
     *
     * @param config the framework's configuration
     * @param lookup the host's configuration by property name
     * @return the new environment, attached to {@code config}
     * @throws IllegalStateException when another open environment is attached
     *                               to {@code config}; the new one is closed
     */
    public static AiEnvironment create(AtmosphereConfig config, Function<String, String> lookup) {
        Objects.requireNonNull(config, "config");
        var environment = create(lookup);
        try {
            environment.attachTo(config);
        } catch (RuntimeException e) {
            environment.close();
            throw e;
        }
        return environment;
    }

    /**
     * The open environment attached to {@code config}, if any.
     *
     * @param config a framework's configuration, may be {@code null}
     * @return the environment, empty when none is attached or it was closed
     */
    public static Optional<AiEnvironment> find(AtmosphereConfig config) {
        if (config == null) {
            return Optional.empty();
        }
        return config.properties().get(PROPERTY) instanceof AiEnvironment environment
                && !environment.isClosed() ? Optional.of(environment) : Optional.empty();
    }

    /**
     * The environment of the application {@code config} belongs to. When no
     * host attached one, it is created from the framework's init-params (the
     * knobs) and the framework's destroy closes it. Such an environment uses
     * the process default's settings while there are any; when there are none,
     * {@link #settingsOrEnvironment()} builds its own from the coordinates
     * {@link AiConfig#LLM_MODE}, {@link AiConfig#LLM_MODEL},
     * {@link AiConfig#LLM_API_KEY} and {@link AiConfig#LLM_BASE_URL}, each read
     * from its {@code LLM_*} environment variable, then the init-param.
     *
     * @param config a framework's configuration; {@code null}, or one that
     *               belongs to no framework, gives {@link #processDefault()}
     * @return the environment, never {@code null}
     */
    public static AiEnvironment of(AtmosphereConfig config) {
        if (config == null || config.framework() == null) {
            return PROCESS_DEFAULT;
        }
        var existing = find(config);
        if (existing.isPresent()) {
            return existing.get();
        }
        var created = open(Kind.LAZY, config::getInitParameter);
        created.attachedTo = config;
        var previous = config.properties().putIfAbsent(PROPERTY, created);
        if (previous instanceof AiEnvironment other && !other.isClosed()) {
            created.close();
            return other;
        }
        if (previous != null && !config.properties().replace(PROPERTY, previous, created)) {
            created.close();
            return of(config);
        }
        config.shutdownHook(created::close);
        return created;
    }

    /**
     * The environment of {@code runtime}: the one it was {@link AgentRuntime#attach attached}
     * to, else {@link #current()}. A runtime that implements {@link AgentRuntime}
     * directly is found among the instances the open environments resolved.
     *
     * @param runtime a runtime, may be {@code null}
     * @return the environment, never {@code null}
     */
    public static AiEnvironment of(AgentRuntime runtime) {
        if (runtime instanceof AbstractAgentRuntime<?> abstractRuntime) {
            return abstractRuntime.environment();
        }
        if (runtime instanceof RoutingAiSupport routing) {
            return routing.environment();
        }
        if (runtime != null) {
            for (var environment : live) {
                for (var resolved : environment.runtimes.resolved()) {
                    if (resolved == runtime) {
                        return environment;
                    }
                }
            }
        }
        return current();
    }

    /**
     * Attach this environment to a framework: {@link #of(AtmosphereConfig)}
     * returns it from now on. Attaching it again to the same configuration does
     * nothing.
     *
     * @param config the framework's configuration
     * @throws IllegalStateException when another open environment is attached
     *                               to {@code config} (two environments for
     *                               one application, one of which nobody would close)
     */
    public void attachTo(AtmosphereConfig config) {
        Objects.requireNonNull(config, "config");
        ensureOpen();
        var previous = config.properties().putIfAbsent(PROPERTY, this);
        if (previous == null || previous == this) {
            attachedTo = config;
            return;
        }
        if (previous instanceof AiEnvironment other && !other.isClosed()) {
            throw new IllegalStateException("The framework already has the AI environment " + other.name()
                    + ", created before " + name + " was attached: attach the host's environment "
                    + "before any @AiEndpoint, @Agent or @Coordinator registers");
        }
        if (!config.properties().replace(PROPERTY, previous, this)) {
            attachTo(config);
            return;
        }
        attachedTo = config;
    }

    private static AiEnvironment open(Kind kind, Function<String, String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        var read = new LinkedHashMap<String, String>();
        for (var property : AiConfig.knobProperties()) {
            String value;
            try {
                value = lookup.apply(property);
            } catch (RuntimeException e) {
                logger.trace("Knob {} could not be read from the host configuration", property, e);
                value = null;
            }
            if (value != null && !value.isBlank()) {
                read.put(property, value.trim());
            }
        }
        var environment = new AiEnvironment(kind,
                "ai-environment-" + IDS.incrementAndGet(), Map.copyOf(read));
        LIVE_LOCK.lock();
        try {
            var next = Arrays.copyOf(live, live.length + 1);
            next[live.length] = environment;
            live = next;
        } finally {
            LIVE_LOCK.unlock();
        }
        // A lazily created environment pushes only values it has, so one with
        // none never hides the host configuration the process default reads.
        if (kind == Kind.HOST || !read.isEmpty()) {
            environment.pushed = AiConfig.pushHostConfig(environment.values, environment);
        }
        if (!read.isEmpty()) {
            logger.info("AI environment {}: knobs {}", environment.name, read.keySet());
        }
        return environment;
    }

    /** One coordinate: its {@code LLM_*} environment variable, then the framework's init-param. */
    private static String coordinate(AtmosphereConfig config, String initParam, String env) {
        var fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        if (config == null) {
            return null;
        }
        String fromParam;
        try {
            fromParam = config.getInitParameter(initParam);
        } catch (RuntimeException e) {
            logger.trace("Init-param {} could not be read", initParam, e);
            fromParam = null;
        }
        return fromParam != null && !fromParam.isBlank() ? fromParam.trim() : null;
    }

    /**
     * The environment the static API reads and writes now: the one bound to
     * the calling thread ({@link #bind}), else the only open one, else
     * {@link #processDefault()}. When several are open and the thread is bound
     * to none, a warning (once per JVM) names them: the process default is
     * then not any one application's.
     *
     * @return the environment, never {@code null}
     */
    public static AiEnvironment current() {
        var bound = BOUND.get();
        if (bound != null && !bound.isClosed()) {
            return bound;
        }
        var open = live;
        if (open.length == 1) {
            return open[0];
        }
        if (open.length > 1 && AMBIGUITY_WARNED.compareAndSet(false, true)) {
            logger.warn("{} AI environments are open in this JVM ({}) and this thread is bound to none: "
                    + "the AiConfig and AgentRuntimeResolver statics read the process default, which is no "
                    + "one application's. Read the application's own with "
                    + "AiEnvironment.of(resource.getAtmosphereConfig()) or "
                    + "AiEnvironment.of(framework.getAtmosphereConfig()).",
                    open.length, Arrays.stream(open).map(AiEnvironment::name).toList());
        }
        return PROCESS_DEFAULT;
    }

    /**
     * The environment bound to the calling thread, if any.
     *
     * @return the bound environment, or {@code null}
     */
    public static AiEnvironment bound() {
        var bound = BOUND.get();
        return bound != null && !bound.isClosed() ? bound : null;
    }

    /**
     * Bind {@code environment} to the calling thread until the returned
     * binding is closed, which restores the previous one. Close it on the same
     * thread, in a {@code finally} or try-with-resources.
     *
     * @param environment the environment, {@code null} to unbind
     * @return the binding to close
     */
    public static Binding bind(AiEnvironment environment) {
        var previous = BOUND.get();
        if (environment == null) {
            BOUND.remove();
        } else {
            BOUND.set(environment);
        }
        return new Binding(previous);
    }

    /** Restores the binding {@link #bind} replaced; closing it twice does nothing. */
    public static final class Binding implements AutoCloseable {

        private final AiEnvironment previous;
        private boolean closed;

        private Binding(AiEnvironment previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }

    /**
     * The open environments, oldest first.
     *
     * @return a snapshot, never {@code null}
     */
    public static List<AiEnvironment> live() {
        return List.of(live);
    }

    /** Drops the cached runtime resolution of the process default and every open environment. */
    static void resetRuntimeCaches() {
        PROCESS_DEFAULT.runtimes.reset();
        for (var environment : live) {
            environment.runtimes.reset();
        }
    }

    /** Drops the cached embedding resolution of the process default and every open environment. */
    static void resetEmbeddingCaches() {
        PROCESS_DEFAULT.embeddings.reset();
        for (var environment : live) {
            environment.embeddings.reset();
        }
    }

    /** Closes every open environment: test hook behind {@link AiConfig#resetHostConfig()}. */
    static void closeAll() {
        for (var environment : live) {
            environment.close();
        }
    }

    /**
     * Close this environment, once: it is no longer open, no longer attached
     * to its framework, its knob values leave the host configuration the
     * process default reads, and its runtime instances and agent settings are
     * dropped (a call still in flight keeps the runtime and the settings it
     * holds; no new runtime is resolved for it). When the process default's
     * settings are this environment's (a static {@code AiConfig.configure}
     * wrote both), they become the latest remaining environment's own, so the
     * static API stops returning a closed application's settings. The clients of
     * the settings this environment built itself ({@link #configure},
     * {@link #settingsOrEnvironment()}, {@link #configureForAgent}) and still
     * holds are shut down ({@link OpenAiCompatibleClient#shutdown()}: a request
     * already sent completes, a later one, such as a call's next tool round,
     * fails), unless the process default now holds those settings. Clients it
     * was given ({@link #installClient}, {@link #adopt}, settings a static
     * {@code AiConfig} mutator built) and native clients bound by the
     * application are not: they belong to the caller. The process default is
     * never closed.
     */
    @Override
    public void close() {
        if (kind == Kind.PROCESS_DEFAULT || !closed.compareAndSet(false, true)) {
            return;
        }
        LIVE_LOCK.lock();
        try {
            live = Arrays.stream(live).filter(e -> e != this).toArray(AiEnvironment[]::new);
        } finally {
            LIVE_LOCK.unlock();
        }
        var config = attachedTo;
        if (config != null) {
            config.properties().remove(PROPERTY, this);
        }
        var own = ownSettings;
        AiConfig.LlmSettings processOwn;
        PROCESS_DEFAULT.lock.lock();
        try {
            if (own != null && PROCESS_DEFAULT.own() == own) {
                AiEnvironment survivor = null;
                for (var environment : live) {
                    if (environment.ownSettings != null) {
                        survivor = environment;
                    }
                }
                if (survivor != null) {
                    PROCESS_DEFAULT.store(survivor.ownSettings, survivor.ownPinned);
                }
            }
            processOwn = PROCESS_DEFAULT.own();
        } finally {
            PROCESS_DEFAULT.lock.unlock();
        }
        var builtHere = new ArrayList<AiConfig.LlmSettings>();
        var built = builtSettings;
        if (built != null && built == own && built != processOwn) {
            builtHere.add(built);
        }
        builtAgentSettings.forEach((agentId, agentBuilt) -> {
            if (agentSettings.get(agentId) == agentBuilt) {
                builtHere.add(agentBuilt);
            }
        });
        var installed = pushed;
        if (installed != null) {
            installed.close();
        }
        runtimes.clear();
        embeddings.clear();
        // Bound and offered native clients belong to the application that
        // handed them over: dropped, never closed.
        boundClients.clear();
        offeredClients.clear();
        agentSettings.clear();
        builtAgentSettings.clear();
        builtSettings = null;
        for (var settings : builtHere) {
            if (settings.client() instanceof OpenAiCompatibleClient client) {
                client.shutdown();
            }
        }
        logger.debug("AI environment {} closed", name);
    }

    /**
     * @return whether {@link #close()} ran; the process default never is
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * @return a name for logs, unique in the JVM
     */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "AiEnvironment[" + name + (isClosed() ? ", closed" : "") + "]";
    }

    private void ensureOpen() {
        if (isClosed()) {
            throw new IllegalStateException("AI environment " + name + " is closed");
        }
    }

    // -- Knobs --

    /**
     * The knob values this environment carries itself (from the host's
     * configuration or the framework's init-params), before system properties
     * and environment variables.
     *
     * @return the non-blank values by knob property name; empty for the process default
     */
    public Map<String, String> values() {
        return values;
    }

    /**
     * The value of one knob, trimmed: the JVM system property, then the knob's
     * {@code LLM_*} environment variable, then this environment's value (for
     * the process default, the host configuration installed last).
     *
     * @param property a knob property name, one of {@link AiConfig#knobProperties()}
     * @return the value, or empty when no source sets it
     * @throws IllegalArgumentException if {@code property} is not a knob
     */
    public Optional<String> knob(String property) {
        var knob = AiConfig.knobFor(property);
        return Optional.ofNullable(rawKnob(knob.property(), knob.env()));
    }

    String rawKnob(String property, String env) {
        return AiConfig.rawKnob(property, env, hostValues());
    }

    /** The host-configuration layer of this environment's knobs. */
    Map<String, String> hostValues() {
        return switch (kind) {
            case PROCESS_DEFAULT -> AiConfig.latestHostValues();
            case HOST -> values;
            case LAZY -> {
                var installed = AiConfig.latestStaticHostValues();
                if (installed.isEmpty()) {
                    yield values;
                }
                if (values.isEmpty()) {
                    yield installed;
                }
                var merged = new HashMap<>(installed);
                merged.putAll(values);
                yield merged;
            }
        };
    }

    /** @return the prompt-cache-key mode this environment's knobs select */
    public PromptCacheKeyMode resolvePromptCacheKeyMode() {
        return AiConfig.promptCacheKeyMode(hostValues());
    }

    /** @return the logprobs mode this environment's knobs select */
    public LogprobsMode resolveLogprobsMode() {
        return AiConfig.logprobsMode(hostValues());
    }

    /** @return the native structured-output mode this environment's knobs select */
    public NativeStructuredOutputMode resolveNativeStructuredOutputMode() {
        return AiConfig.nativeStructuredOutputMode(hostValues());
    }

    /** @return the planning mode this environment's knobs select */
    public org.atmosphere.ai.plan.PlanningMode resolvePlanningMode() {
        return AiConfig.planningMode(hostValues());
    }

    /** @return the filesystem mode this environment's knobs select */
    public org.atmosphere.ai.fs.FilesystemMode resolveFilesystemMode() {
        return AiConfig.filesystemMode(hostValues());
    }

    /** @return the generation parameters this environment's knobs set */
    public GenerationParams resolveGenerationParams() {
        return AiConfig.generationParams(hostValues());
    }

    /** @return the embedding model this environment's knobs name */
    public Optional<String> resolveEmbeddingModel() {
        return AiConfig.embeddingModel(hostValues());
    }

    /** @return the reattach grace this environment's knobs set */
    public Duration resolveReattachGrace() {
        return AiConfig.reattachGrace(hostValues());
    }

    // -- Settings --

    /**
     * This environment's LLM settings, or, when it has none of its own, the
     * process default's.
     *
     * @return the settings, or {@code null} when none are configured
     */
    public AiConfig.LlmSettings settings() {
        var own = own();
        if (own != null || kind == Kind.PROCESS_DEFAULT) {
            return own;
        }
        return PROCESS_DEFAULT.own();
    }

    /** @return whether this environment holds settings of its own */
    public boolean hasOwnSettings() {
        return own() != null;
    }

    /** The pinned generation values of {@link #settings()}. */
    GenerationParams pinned() {
        if (own() != null || kind == Kind.PROCESS_DEFAULT) {
            return ownPins();
        }
        return PROCESS_DEFAULT.ownPins();
    }

    private AiConfig.LlmSettings own() {
        return kind == Kind.PROCESS_DEFAULT ? AiConfig.processSettings() : ownSettings;
    }

    private GenerationParams ownPins() {
        return kind == Kind.PROCESS_DEFAULT ? AiConfig.processPinned() : ownPinned;
    }

    /** Store this environment's own settings; the caller holds {@link #lock}. */
    private void store(AiConfig.LlmSettings stored, GenerationParams pins) {
        if (kind == Kind.PROCESS_DEFAULT) {
            AiConfig.storeProcessSettings(stored, pins);
        } else {
            ownSettings = stored;
            ownPinned = pins;
        }
    }

    /**
     * Build settings with this environment's knobs and make them its own.
     *
     * @param mode    {@code remote}, {@code local} or {@code fake}
     * @param model   model name
     * @param apiKey  API key, may be {@code null}
     * @param baseUrl explicit base URL, {@code null} for auto-detection
     * @return the settings now in effect
     */
    public AiConfig.LlmSettings configure(String mode, String model, String apiKey, String baseUrl) {
        return configure(mode, model, apiKey, baseUrl, GenerationParams.defaults());
    }

    /**
     * As {@link #configure(String, String, String, String)}, with generation
     * values that win over the generation knobs for these settings only.
     *
     * @param pinned generation values for these settings, {@code null} for none
     * @return the settings now in effect
     */
    public AiConfig.LlmSettings configure(String mode, String model, String apiKey, String baseUrl,
                                          GenerationParams pinned) {
        var pins = pinned != null ? pinned : GenerationParams.defaults();
        var built = buildSettings(mode, model, apiKey, baseUrl, pins);
        own(built, pins, true);
        return built;
    }

    /**
     * Replace the client of this environment's settings, keeping every other
     * component, and make the result its own.
     *
     * @param client the client to install
     * @return the settings now in effect
     * @throws IllegalStateException when this environment has no settings yet
     */
    public AiConfig.LlmSettings installClient(LlmClient client) {
        Objects.requireNonNull(client, "client must not be null");
        lock.lock();
        try {
            var current = settings();
            if (current == null) {
                throw new IllegalStateException("AiEnvironment.installClient called before configure() — "
                        + "there is no resolved client to wrap");
            }
            var installed = AiConfig.withClient(current, client);
            own(installed, pinned(), false);
            logger.info("AI environment {}: installed client {} (model={}, mode={})", name,
                    client.getClass().getSimpleName(), current.model(), current.mode());
            return installed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Make {@code adopted} this environment's own settings, as built: an
     * application's own {@code LlmSettings} bean.
     *
     * @param adopted the settings
     */
    public void adopt(AiConfig.LlmSettings adopted) {
        Objects.requireNonNull(adopted, "settings");
        own(adopted, GenerationParams.defaults(), false);
    }

    private void own(AiConfig.LlmSettings settings, GenerationParams pins, boolean builtHere) {
        lock.lock();
        try {
            store(settings, pins);
            builtSettings = builtHere ? settings : null;
        } finally {
            lock.unlock();
        }
        // A runtime resolved before (a demo runtime available because no
        // settings existed) is resolved again against these.
        runtimes.reset();
    }

    /**
     * The settings in effect ({@link #settings()}), or, when there are none,
     * settings built from the {@code LLM_*} environment variables with this
     * environment's knobs and kept as its own. The process default publishes
     * them as the static {@code AiConfig.get()} value; no other environment
     * does.
     *
     * @return the settings, never {@code null}
     */
    public AiConfig.LlmSettings settingsOrEnvironment() {
        var current = settings();
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            current = settings();
            if (current == null) {
                current = buildFromCoordinates();
                store(current, GenerationParams.defaults());
                builtSettings = current;
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Settings built as {@link #settingsOrEnvironment()} builds them when
     * there are none (from the {@code LLM_*} environment variables, then, for
     * an environment created from init-params, the init-params), with this
     * environment's knobs, for the caller only: nothing is kept, here or in
     * the process default. For a request-time fallback, which must read the
     * settings, never write them.
     *
     * @return new settings, never {@code null}
     */
    public AiConfig.LlmSettings settingsFromEnvironment() {
        return buildFromCoordinates();
    }

    /**
     * Settings from the {@code LLM_*} environment variables, and for an
     * environment created from init-params the init-params after them, built
     * with this environment's knobs.
     */
    private AiConfig.LlmSettings buildFromCoordinates() {
        var config = kind == Kind.LAZY ? attachedTo : null;
        if (config == null) {
            return AiConfig.buildFromEnvironment(this);
        }
        var mode = coordinate(config, AiConfig.LLM_MODE, "LLM_MODE");
        var model = coordinate(config, AiConfig.LLM_MODEL, "LLM_MODEL");
        var apiKey = coordinate(config, AiConfig.LLM_API_KEY, "LLM_API_KEY");
        if (apiKey == null) {
            apiKey = coordinate(null, null, "OPENAI_API_KEY");
        }
        if (apiKey == null) {
            apiKey = coordinate(null, null, "GEMINI_API_KEY");
        }
        return buildSettings(mode != null ? mode : AiConfig.DEFAULT_MODE,
                model != null ? model : AiConfig.DEFAULT_MODEL, apiKey,
                coordinate(config, AiConfig.LLM_BASE_URL, "LLM_BASE_URL"), GenerationParams.defaults());
    }

    /** Build settings with this environment's knobs, without keeping them. */
    AiConfig.LlmSettings buildSettings(String mode, String model, String apiKey, String baseUrl,
                                       GenerationParams pinned) {
        return AiConfig.buildSettings(mode, model, apiKey, baseUrl,
                pinned != null ? pinned : GenerationParams.defaults(), this);
    }

    /**
     * Keep settings a static mutator built: in the process default, as the
     * static API always did, and also in this environment when it holds
     * settings of its own (a host's, or ones its init-params seeded), so the
     * application the static call targets uses them.
     */
    void publishFromStatic(AiConfig.LlmSettings built, GenerationParams pins) {
        if (kind != Kind.PROCESS_DEFAULT && (kind == Kind.HOST || ownSettings != null)) {
            lock.lock();
            try {
                store(built, pins);
            } finally {
                lock.unlock();
            }
        }
        PROCESS_DEFAULT.lock.lock();
        try {
            PROCESS_DEFAULT.store(built, pins);
        } finally {
            PROCESS_DEFAULT.lock.unlock();
        }
    }

    /**
     * Clears the settings of the process default and every open environment,
     * and which workspace owns them. Test hook behind {@code AiConfig.resetForTesting()}.
     */
    static void resetSettingsForTesting() {
        PROCESS_DEFAULT.clearSettings();
        for (var environment : live) {
            environment.clearSettings();
        }
    }

    private void clearSettings() {
        lock.lock();
        try {
            store(null, GenerationParams.defaults());
        } finally {
            lock.unlock();
        }
        resetSettingsOwner();
    }

    /**
     * Configure settings for one agent of this application, built with this
     * environment's knobs; nothing else changes.
     *
     * @param pinned generation values for this agent, {@code null} for none
     * @return the agent's settings
     */
    public AiConfig.LlmSettings configureForAgent(String agentId, String mode, String model,
                                                  String apiKey, String baseUrl, GenerationParams pinned) {
        Objects.requireNonNull(agentId, "agentId");
        var built = buildSettings(mode, model, apiKey, baseUrl, pinned);
        agentSettings.put(agentId, built);
        builtAgentSettings.put(agentId, built);
        configLogger.info("AI config scoped to agent '{}': mode={}, model={}", agentId, mode, model);
        return built;
    }

    /** Keep agent settings a static mutator built here and in the process default. */
    void publishAgentFromStatic(String agentId, AiConfig.LlmSettings built) {
        agentSettings.put(agentId, built);
        if (kind != Kind.PROCESS_DEFAULT) {
            PROCESS_DEFAULT.agentSettings.put(agentId, built);
        }
    }

    /**
     * The agent's settings: its own ({@link #configureForAgent}), else
     * {@link #settings()}.
     *
     * @param agentId the agent id, may be {@code null}
     * @return the settings, or {@code null} when none are configured
     */
    public AiConfig.LlmSettings forAgent(String agentId) {
        var scoped = agentScoped(agentId);
        return scoped != null ? scoped : settings();
    }

    /**
     * The agent-scoped settings, here or, failing that, in the process default.
     *
     * @param agentId the agent id, may be {@code null}
     * @return the settings, or {@code null} when the agent has none
     */
    public AiConfig.LlmSettings agentScoped(String agentId) {
        if (agentId == null) {
            return null;
        }
        var scoped = agentSettings.get(agentId);
        if (scoped != null || kind == Kind.PROCESS_DEFAULT) {
            return scoped;
        }
        return PROCESS_DEFAULT.agentSettings.get(agentId);
    }

    /**
     * Remove an agent's scoped settings from this environment.
     *
     * @param agentId the agent id, may be {@code null}
     */
    public void resetAgent(String agentId) {
        if (agentId != null) {
            agentSettings.remove(agentId);
            builtAgentSettings.remove(agentId);
        }
    }

    /**
     * Claim the settings of this environment for a workspace's
     * {@code RUNTIME.md}: the first claim wins.
     *
     * @param workspace the workspace name
     * @return the workspace that owns them, {@code workspace} when this claim won
     */
    public String claimSettingsOwner(String workspace) {
        return settingsOwner.compareAndSet(null, workspace) ? workspace : settingsOwner.get();
    }

    /**
     * Give the settings of this environment to a workspace, whoever held them.
     *
     * @param workspace the workspace name, {@code null} to forget the owner
     * @return the workspace that owned them before, or {@code null}
     */
    public String replaceSettingsOwner(String workspace) {
        return settingsOwner.getAndSet(workspace);
    }

    /** Forget which workspace owns the settings. Test hook. */
    void resetSettingsOwner() {
        settingsOwner.set(null);
    }

    /**
     * Names, in a warning, the knobs the settings in use do not apply (built
     * before this environment's knobs, in {@code main()} or a static
     * initializer), so a value set in the application's configuration is not
     * silently ignored (Correctness Invariant #5). A host calls it once its
     * application started.
     *
     * @return the knob property names not in effect, empty when the settings
     *         apply every knob or none are configured
     */
    public List<String> reportKnobsNotInEffect() {
        var current = settings();
        if (current == null) {
            return List.of();
        }
        var notInEffect = knobsNotInEffect(current, pinned());
        if (!notInEffect.isEmpty()) {
            configLogger.warn("The AI settings in use were built before the application's configuration was "
                    + "installed and do not apply {}: build them with AiConfig.configure once the "
                    + "application started, or set these knobs as -D system properties or LLM_* "
                    + "environment variables", notInEffect);
        }
        return notInEffect;
    }

    /** The knobs read when settings are built whose value here differs from the one {@code built} carries. */
    List<String> knobsNotInEffect(AiConfig.LlmSettings built, GenerationParams pins) {
        return AiConfig.knobsNotInEffect(built, pins, this);
    }

    // -- Runtimes --

    /**
     * This environment's runtimes: its own {@code ServiceLoader} instances,
     * attached to it, selected by its {@link AiConfig#RUNTIME_PROPERTY} knob.
     *
     * @return the registry, never {@code null}
     */
    public AgentRuntimeRegistry runtimes() {
        return runtimes;
    }

    /**
     * This environment's embedding runtimes.
     *
     * @return the registry, never {@code null}
     */
    public EmbeddingRuntimeRegistry embeddings() {
        return embeddings;
    }

    // -- Native-client bindings --

    /**
     * Bind a native client to a runtime adapter for this application only:
     * its runtime instances of {@code adapter} use {@code client}, whatever
     * the adapter's static binder holds. Replaces this environment's previous
     * binding of the same slot; {@code null} removes it. The runtimes resolved
     * so far are dropped, so the next resolution creates instances that use
     * it. The client stays the caller's: {@link #close()} does not close it.
     *
     * <p>Runtime adapters wrap this in typed binders (for example
     * {@code SpringAiAgentRuntime.setChatClient(AiEnvironment, ChatClient)}),
     * which also {@linkplain #markExplicitClientBinding() mark} an explicit
     * binding when their static binder does.</p>
     *
     * @param adapter the runtime adapter class that owns the slot
     * @param type    the native client type of the slot
     * @param client  the client, {@code null} to remove the binding
     * @param <T>     the native client type
     */
    public <T> void bindClient(Class<?> adapter, Class<T> type, T client) {
        var key = new BindingKey(adapter, type);
        if (client == null) {
            boundClients.remove(key);
        } else {
            ensureOpen();
            boundClients.put(key, type.cast(client));
        }
        resetResolutions();
    }

    /**
     * Offer a native client to a runtime adapter for this application, as an
     * auto-configuration does: it is used only when neither this environment
     * nor the adapter's static binder holds an explicit one, and a client
     * offered earlier is kept.
     *
     * @param adapter the runtime adapter class that owns the slot
     * @param type    the native client type of the slot
     * @param client  the client
     * @param <T>     the native client type
     * @return {@code true} when this offer is now the environment's offered
     *         client, {@code false} when another was offered first
     */
    public <T> boolean offerClient(Class<?> adapter, Class<T> type, T client) {
        Objects.requireNonNull(client, "client");
        ensureOpen();
        if (offeredClients.putIfAbsent(new BindingKey(adapter, type), type.cast(client)) != null) {
            return false;
        }
        resetResolutions();
        return true;
    }

    /**
     * The native client a runtime adapter's instance of this application uses,
     * first found: the client {@linkplain #bindClient bound} here, then
     * {@code processBinding} (what the adapter's static binder holds, which
     * applies to every application), then the client {@linkplain #offerClient offered}
     * here.
     *
     * @param adapter        the runtime adapter class that owns the slot
     * @param type           the native client type of the slot
     * @param processBinding the adapter's static binding, may be {@code null}
     * @param <T>            the native client type
     * @return the client, or {@code null} when there is none
     */
    public <T> T client(Class<?> adapter, Class<T> type, T processBinding) {
        var key = new BindingKey(adapter, type);
        var bound = boundClients.get(key);
        if (bound != null) {
            return type.cast(bound);
        }
        if (processBinding != null) {
            return processBinding;
        }
        var offered = offeredClients.get(key);
        return offered != null ? type.cast(offered) : null;
    }

    /**
     * Record that this application explicitly bound a native client to a
     * runtime adapter, so the demo runtime, which answers only when nothing
     * real can serve, yields to it ({@link #hasExplicitBinding()}).
     */
    public void markExplicitClientBinding() {
        explicitBinding = true;
        runtimes.reset();
    }

    /**
     * Whether a native client was explicitly bound for this application:
     * through this environment ({@link #markExplicitClientBinding()}) or, for
     * every application, through a runtime adapter's static binder
     * ({@link AgentRuntimeResolver#hasExplicitClientBinding()}).
     *
     * @return {@code true} once an explicit binding happened
     */
    public boolean hasExplicitBinding() {
        return explicitBinding || AgentRuntimeResolver.hasExplicitClientBinding();
    }

    /** Clears the bindings and the explicit-binding mark of this environment. Test hook. */
    void clearBindings() {
        boundClients.clear();
        offeredClients.clear();
        explicitBinding = false;
        resetResolutions();
    }

    /** Clears the bindings of the process default and every open environment. Test hook. */
    static void clearAllBindings() {
        PROCESS_DEFAULT.clearBindings();
        for (var environment : live) {
            environment.clearBindings();
        }
    }

    private void resetResolutions() {
        runtimes.reset();
        embeddings.reset();
    }
}
