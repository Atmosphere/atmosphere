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
package org.atmosphere.ai.decision.typesafe;

import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionModel;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.DecisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * A {@link DecisionModel} over the TypeSafe System One API
 * ({@code POST /v1/systemone}, documented at {@code https://docs.typesafe.ai/api.md}).
 * Every question of a {@link DecisionRequest} goes out in one HTTP request and
 * comes back as one typed {@link Answer}; nothing streams.
 *
 * <h2>Configuration</h2>
 * The no-argument constructor (the one {@link java.util.ServiceLoader} calls)
 * reads, JVM system property first and environment variable second:
 * <ul>
 *   <li>the API key — {@value #API_KEY_PROPERTY} / {@value #API_KEY_ENV};</li>
 *   <li>the base URL — {@value #BASE_URL_PROPERTY} / {@value #BASE_URL_ENV}, default
 *       {@value #DEFAULT_BASE_URL}. Plain {@code http} is accepted only for a
 *       loopback host, so the key never crosses a network in the clear;</li>
 *   <li>the model — {@value #MODEL_PROPERTY} / {@value #MODEL_ENV}, default the
 *       pinned {@value #DEFAULT_MODEL}. {@code jev-latest} and {@code jev-preview}
 *       are moving aliases: the answers behind them can change without a change
 *       here, so they are accepted but logged, once per alias.</li>
 * </ul>
 * The environment variable names are the ones the TypeSafe SDKs read. An
 * invalid configuration does not throw from the no-argument constructor (that
 * would only make {@link java.util.ServiceLoader} skip the provider silently):
 * the instance reports {@link #isAvailable()} {@code false}, logs why once per
 * distinct error (not once per instance: each {@link java.util.ServiceLoader}
 * scan builds a new one), and fails every question with
 * {@link Answer.Failed.Reason#ERROR}. {@link #builder()}
 * configures an instance explicitly and throws on invalid input.
 *
 * <h2>Availability</h2>
 * {@link #isAvailable()} is {@code true} only after a {@code GET /v1/models}
 * with the key returned {@code 200} and the documented
 * {@code {"models":[{"name":...}]}} body — never because a key is set. The
 * verdict is cached ({@value #DEFAULT_AVAILABLE_TTL_SECONDS} s when reachable,
 * {@value #DEFAULT_UNAVAILABLE_TTL_SECONDS} s when not) and shared by every
 * instance with the same base URL, model, key and clock, so the fresh instance
 * each {@link java.util.ServiceLoader} scan creates reuses it instead of probing
 * again. One probe runs at a time per such configuration, and a {@code 401} or
 * {@code 403} from {@code decide} marks it unavailable until the next probe. No
 * key means no probe and {@code false}.
 *
 * <p>The probe proves the key and the endpoint, not the model. The list holds
 * the aliases, and {@code docs.typesafe.ai/models.md} says versioned IDs are
 * accepted whether or not they are listed, so the configured model is not
 * looked up in it. A well-formed model id that does not exist (a typo, a
 * retired version) is therefore available, is selected, and fails every
 * question.</p>
 *
 * <h2>Failures</h2>
 * Every question resolves to exactly one {@link Answer} before
 * {@link DecisionRequest#timeout()}; a failure of the HTTP exchange fails every
 * question of the request the same way:
 * <ul>
 *   <li>{@code 408}, {@code 429}, {@code 5xx} (including {@code 529 Overloaded})
 *       and connection errors (a TCP connect timeout included) are retried up
 *       to {@code maxRetries} times. The
 *       wait is the {@code retry-after-ms} header, else {@code Retry-After}
 *       (seconds or an HTTP date), else exponential backoff from
 *       {@value #INITIAL_BACKOFF_MILLIS} ms up to {@value #MAX_BACKOFF_MILLIS} ms.
 *       A wait that would reach the deadline is not taken: the request stops
 *       there. A final {@code 429} or {@code 529} is
 *       {@link Answer.Failed.Reason#CAPACITY}, a final connection error
 *       {@link Answer.Failed.Reason#ERROR} naming the attempt, anything else
 *       {@link Answer.Failed.Reason#ERROR};</li>
 *   <li>{@code 401}, {@code 403}, {@code 422} and any other status are not
 *       retried: {@link Answer.Failed.Reason#ERROR} naming the status, the
 *       provider's message and its {@code x-typesafe-request-id};</li>
 *   <li>the deadline passing is {@link Answer.Failed.Reason#TIMEOUT} (the
 *       in-flight exchange is cancelled), including while TCP is still
 *       connecting: a connect is a retried connection error only when the
 *       {@code connectTimeout} bound, shorter than the time left, cut it short;
 *       no free slot before the deadline is
 *       {@link Answer.Failed.Reason#CAPACITY};</li>
 *   <li>a body over {@value #MAX_RESPONSE_BYTES} bytes, or a {@code 200} that is
 *       not the documented shape, is {@link Answer.Failed.Reason#UNPARSEABLE};
 *       an answer outside what its question allows is
 *       {@link Answer.Failed.Reason#INVALID_ANSWER} (see {@link TypesafeWire}).</li>
 * </ul>
 *
 * <h2>Confidence</h2>
 * Every answer's confidence has source
 * {@link org.atmosphere.ai.AiConfidence.Source#PROVIDER_DISTRIBUTION}. A choice
 * or score carries the provider's {@code probabilities} and {@code confidence}
 * as returned. A noul carries {@code p = P(true)} as
 * {@link Answer.Noul#probabilityTrue()}, {@code value = p >= 0.5}, and confidence
 * {@code |2p - 1|}: the provider returns no confidence for a noul.
 *
 * <h2>Lifecycle</h2>
 * The instance creates its {@link HttpClient} on its first request (an instance
 * that only reads a cached verdict never creates one), owns it and closes it in
 * {@link #close()}, which is idempotent and aborts the exchanges in flight
 * ({@link HttpClient#shutdownNow()}) instead of waiting for them: a question in
 * flight then fails with {@link Answer.Failed.Reason#ERROR} ("closed"), and so
 * does every later one, and the model is unavailable. At most
 * {@code maxConcurrency} requests ({@value #DEFAULT_MAX_CONCURRENCY} by default)
 * are in flight per instance. The instance
 * {@link org.atmosphere.ai.decision.DecisionModelResolver} selects is closed by
 * its {@code reset()}; nothing else closes it.
 *
 * <h2>As a safety backend</h2>
 * Registered through {@code META-INF/services}, this model is what
 * {@link org.atmosphere.ai.decision.DecisionModelResolver} returns once it is
 * available, so the {@code LLM_CLASSIFIER} injection, scope and moderation
 * tiers ask it their questions. The injection tier still runs it on the
 * rule-based floor ({@code InjectionClassifierResolver}): a model, this one
 * included, can only add recall there, never clear what the rules flag. An
 * injection tier first resolved while no model could answer (this one
 * unavailable, only the demo runtime installed) stays rule-based until
 * {@code InjectionClassifierResolver.reset()}, even once this model is
 * available.
 */
public final class TypesafeDecisionModel implements DecisionModel, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TypesafeDecisionModel.class);

    /** Default API base URL (the SDKs' {@code DEFAULT_BASE_URL}). */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";

    /** Default model: a pinned version id, not a moving alias. */
    public static final String DEFAULT_MODEL = "jev-1.13.0";

    /** Environment variable for the API key (the SDKs' {@code API_KEY_ENV}). */
    public static final String API_KEY_ENV = "TYPESAFE_API_KEY";

    /** Environment variable for the base URL (the SDKs' {@code BASE_URL_ENV}). */
    public static final String BASE_URL_ENV = "TYPESAFE_BASE_URL";

    /** Environment variable for the model (the SDKs' {@code DEFAULT_MODEL_ENV}). */
    public static final String MODEL_ENV = "TYPESAFE_DEFAULT_MODEL";

    /** JVM system property for the API key; wins over {@value #API_KEY_ENV}. */
    public static final String API_KEY_PROPERTY = "org.atmosphere.ai.decision.typesafe.api-key";

    /** JVM system property for the base URL; wins over {@value #BASE_URL_ENV}. */
    public static final String BASE_URL_PROPERTY = "org.atmosphere.ai.decision.typesafe.base-url";

    /** JVM system property for the model; wins over {@value #MODEL_ENV}. */
    public static final String MODEL_PROPERTY = "org.atmosphere.ai.decision.typesafe.model";

    /** Selection priority among registered decision models. */
    public static final int DEFAULT_PRIORITY = 100;

    /** Retries after the first attempt (the SDKs' default). */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Requests in flight per instance. */
    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    /** How long a successful availability probe is trusted, in seconds. */
    public static final long DEFAULT_AVAILABLE_TTL_SECONDS = 300;

    /** How long a failed availability probe is trusted, in seconds. */
    public static final long DEFAULT_UNAVAILABLE_TTL_SECONDS = 30;

    /** Bound on one availability probe, in seconds. */
    public static final long PROBE_TIMEOUT_SECONDS = 5;

    /** Largest response body read, in bytes. */
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** First retry backoff when the server names no wait, in milliseconds. */
    public static final long INITIAL_BACKOFF_MILLIS = 500;

    /** Largest retry backoff when the server names no wait, in milliseconds. */
    public static final long MAX_BACKOFF_MILLIS = 5_000;

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** The production clock: one instance, so instances on it share verdicts. */
    private static final LongSupplier SYSTEM_CLOCK = System::nanoTime;

    /** Bound on the shared availability verdicts kept; the least recently used goes first. */
    static final int MAX_SHARED_VERDICTS = 64;

    /** Bound on the distinct configuration messages remembered as logged. */
    static final int MAX_LOGGED_ONCE = 64;

    /** How long {@link #close()} waits for the aborted exchanges to finish, in seconds. */
    static final long CLOSE_WAIT_SECONDS = 5;

    /**
     * Configuration messages already logged. Keyed by the message, not held per
     * instance: every {@link java.util.ServiceLoader} scan builds a new instance,
     * and a per-instance flag would log once per safety check.
     */
    private static final Set<String> LOGGED_ONCE = Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_LOGGED_ONCE;
                }
            });

    private static final Map<VerdictKey, SharedVerdict> SHARED_VERDICTS =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<VerdictKey, SharedVerdict> eldest) {
                    return size() > MAX_SHARED_VERDICTS;
                }
            };

    private final String apiKey;
    private final URI baseUri;
    private final String model;
    private final String configError;
    private final int priority;
    private final int maxRetries;
    private final Duration availableTtl;
    private final Duration unavailableTtl;
    private final LongSupplier nanoClock;
    private final Duration connectTimeout;
    private final Semaphore slots;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** The verdict shared with every instance of the same configuration; null without a usable key. */
    private final SharedVerdict verdict;
    private final Object clientLock = new Object();
    /** Created on the first request, closed by {@link #close()}; guarded by {@link #clientLock}. */
    private HttpClient client;

    /** One probe's verdict and when it was taken. */
    private record Availability(boolean up, long checkedAtNanos, String detail) {
    }

    /**
     * What a verdict is shared by: the endpoint, the model, a SHA-256 digest of
     * the key (the key itself is never kept here) and the clock its times are on.
     */
    private record VerdictKey(URI baseUri, String model, String keyDigest, LongSupplier clock) {
    }

    /**
     * A configuration's latest verdict, and the lock that keeps its probes one at
     * a time. A {@link ReentrantLock}, not a monitor: the probe waits on the
     * network, and a virtual thread blocked inside {@code synchronized} pins its
     * carrier on JDK 21.
     */
    private static final class SharedVerdict {
        private final ReentrantLock probeLock = new ReentrantLock();
        private volatile Availability availability;
    }

    /**
     * Reads the configuration from system properties and the environment (see
     * the class Javadoc). Never throws on a bad value: the instance is then
     * unavailable.
     */
    public TypesafeDecisionModel() {
        this(fromEnvironment());
    }

    private TypesafeDecisionModel(Builder builder) {
        String error = null;
        URI uri = null;
        try {
            uri = validateBaseUrl(builder.baseUrl);
            validateModel(builder.model);
            if (builder.apiKey != null) {
                validateKey(builder.apiKey);
            }
        } catch (IllegalArgumentException e) {
            if (builder.strict) {
                throw e;
            }
            error = e.getMessage();
        }
        this.apiKey = builder.apiKey;
        this.baseUri = uri;
        this.model = builder.model;
        this.configError = error;
        this.priority = builder.priority;
        this.maxRetries = builder.maxRetries;
        this.availableTtl = builder.availableTtl;
        this.unavailableTtl = builder.unavailableTtl;
        this.nanoClock = builder.nanoClock;
        this.connectTimeout = builder.connectTimeout;
        this.slots = new Semaphore(builder.maxConcurrency, true);
        this.verdict = error == null && apiKey != null
                ? sharedVerdict(new VerdictKey(uri, model, digest(apiKey), nanoClock)) : null;
        if (error == null && isMovingAlias(model) && firstTime("alias:" + model)) {
            logger.info("TypeSafe model '{}' is a moving alias; answers can change when it moves. "
                    + "Pin a version id such as {} to keep tuned thresholds stable.", model, DEFAULT_MODEL);
        }
    }

    /** A builder for an explicitly configured instance. */
    public static Builder builder() {
        return new Builder(true);
    }

    private static Builder fromEnvironment() {
        var builder = new Builder(false);
        builder.apiKey = blankToNull(setting(API_KEY_PROPERTY, API_KEY_ENV));
        var base = blankToNull(setting(BASE_URL_PROPERTY, BASE_URL_ENV));
        if (base != null) {
            builder.baseUrl = base.strip();
        }
        var configuredModel = blankToNull(setting(MODEL_PROPERTY, MODEL_ENV));
        if (configuredModel != null) {
            builder.model = configuredModel.strip();
        }
        return builder;
    }

    private static String setting(String property, String env) {
        var value = System.getProperty(property);
        return value != null ? value : System.getenv(env);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static SharedVerdict sharedVerdict(VerdictKey key) {
        synchronized (SHARED_VERDICTS) {
            return SHARED_VERDICTS.computeIfAbsent(key, k -> new SharedVerdict());
        }
    }

    /** True the first time {@code key} is seen (within the {@value #MAX_LOGGED_ONCE} remembered). */
    static boolean firstTime(String key) {
        synchronized (LOGGED_ONCE) {
            return LOGGED_ONCE.add(key);
        }
    }

    /** Number of configuration messages remembered as logged (bounded by {@value #MAX_LOGGED_ONCE}). */
    static int loggedOnceCount() {
        synchronized (LOGGED_ONCE) {
            return LOGGED_ONCE.size();
        }
    }

    /** Test hook: forget which configuration messages were logged. */
    static void forgetLoggedOnce() {
        synchronized (LOGGED_ONCE) {
            LOGGED_ONCE.clear();
        }
    }

    /** Test hook: forget every shared verdict, so the next instance probes. */
    static void forgetSharedVerdicts() {
        synchronized (SHARED_VERDICTS) {
            SHARED_VERDICTS.clear();
        }
    }

    /** Number of shared verdicts held (bounded by {@value #MAX_SHARED_VERDICTS}). */
    static int sharedVerdictCount() {
        synchronized (SHARED_VERDICTS) {
            return SHARED_VERDICTS.size();
        }
    }

    private static String digest(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256 (MessageDigest Javadoc).
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The owned client, created on first use; {@code null} once closed. */
    private HttpClient client() {
        synchronized (clientLock) {
            if (closed.get()) {
                return null;
            }
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            }
            return client;
        }
    }

    /** Whether this instance has created its {@link HttpClient} (and not closed it). */
    boolean hasClient() {
        synchronized (clientLock) {
            return client != null;
        }
    }

    static boolean isMovingAlias(String model) {
        return "jev-latest".equals(model) || "jev-preview".equals(model);
    }

    @Override
    public String name() {
        return "typesafe:" + model;
    }

    @Override
    public int priority() {
        return priority;
    }

    /** The model id sent in every request. */
    public String model() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        if (closed.get() || verdict == null) {
            if (configError != null && firstTime("config:" + configError)) {
                logger.warn("TypeSafe decision model is misconfigured and unavailable: {}", configError);
            }
            return false;
        }
        var current = verdict.availability;
        if (fresh(current)) {
            return current.up();
        }
        verdict.probeLock.lock();
        try {
            current = verdict.availability;
            if (fresh(current)) {
                return current.up();
            }
            var probed = probe();
            if (probed == null) {
                // Closed or interrupted while probing: this instance's or this
                // caller's state, not the configuration's.
                return false;
            }
            if (current == null || current.up() != probed.up()) {
                if (probed.up()) {
                    logger.info("TypeSafe API at {} accepted the key (GET /v1/models); model {} is not checked "
                            + "by this probe", baseUri, model);
                } else {
                    logger.warn("TypeSafe decision model {} is unavailable: {}", model, probed.detail());
                }
            }
            verdict.availability = probed;
            return probed.up();
        } finally {
            verdict.probeLock.unlock();
        }
    }

    private boolean fresh(Availability snapshot) {
        if (snapshot == null) {
            return false;
        }
        var ttl = snapshot.up() ? availableTtl : unavailableTtl;
        // Compared as Durations: ttl.toNanos() overflows past about 292 years.
        return Duration.ofNanos(nanoClock.getAsLong() - snapshot.checkedAtNanos()).compareTo(ttl) < 0;
    }

    /**
     * One {@code GET /v1/models}; {@code null} when this instance is closed or the
     * calling thread is interrupted, since neither says anything about the endpoint.
     */
    private Availability probe() {
        var http = client();
        if (http == null) {
            return null;
        }
        var request = HttpRequest.newBuilder(baseUri.resolve("/v1/models"))
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        var future = http.sendAsync(request, BoundedBodySubscriber.handler(MAX_RESPONSE_BYTES));
        try {
            var response = future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                return down("GET /v1/models returned " + response.statusCode() + " "
                        + TypesafeWire.errorMessage(response.body()) + requestId(response.headers()));
            }
            if (!TypesafeWire.isModelList(response.body())) {
                return down("GET /v1/models returned 200 without the documented {\"models\":[...]} body");
            }
            return new Availability(true, nanoClock.getAsLong(), "");
        } catch (TimeoutException e) {
            future.cancel(true);
            return down("GET /v1/models did not answer within " + PROBE_TIMEOUT_SECONDS + " s");
        } catch (ExecutionException e) {
            if (closed.get()) {
                // close() aborted the probe: no verdict on the configuration.
                return null;
            }
            return down("GET /v1/models failed: " + e.getCause());
        } catch (InterruptedException e) {
            // The caller's thread state, not the endpoint's: no verdict, so an
            // interrupted caller never marks the configuration down for others.
            future.cancel(true);
            Thread.currentThread().interrupt();
            logger.debug("TypeSafe availability probe interrupted; no verdict recorded", e);
            return null;
        } catch (RuntimeException e) {
            return down("GET /v1/models failed: " + e);
        }
    }

    private Availability down(String detail) {
        return new Availability(false, nanoClock.getAsLong(), detail);
    }

    private void markUnavailable(String detail) {
        verdict.availability = down(detail);
    }

    @Override
    public DecisionResult decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        var start = System.nanoTime();
        var deadline = start + request.timeout().toNanos();
        if (closed.get()) {
            return failed(request, start, Answer.Failed.Reason.ERROR, "TypeSafe decision model is closed");
        }
        if (configError != null) {
            return failed(request, start, Answer.Failed.Reason.ERROR, "TypeSafe decision model is misconfigured: "
                    + configError);
        }
        if (apiKey == null) {
            return failed(request, start, Answer.Failed.Reason.ERROR, "no TypeSafe API key ("
                    + API_KEY_PROPERTY + " or " + API_KEY_ENV + ")");
        }
        var body = TypesafeWire.encodeRequest(model, request);
        try {
            if (!slots.tryAcquire(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return failed(request, start, Answer.Failed.Reason.CAPACITY,
                        "no free slot before the deadline (" + slots.availablePermits() + " free)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(request, start, Answer.Failed.Reason.ERROR, "interrupted while waiting for a slot");
        }
        try {
            return exchange(request, body, start, deadline);
        } finally {
            slots.release();
        }
    }

    private DecisionResult exchange(DecisionRequest request, byte[] body, long start, long deadline) {
        var uri = baseUri.resolve("/v1/systemone");
        for (var attempt = 0; ; attempt++) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return failed(request, start, Answer.Failed.Reason.TIMEOUT, "deadline passed before attempt "
                        + (attempt + 1));
            }
            var httpRequest = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            var http = client();
            if (http == null) {
                return failed(request, start, Answer.Failed.Reason.ERROR, "TypeSafe decision model is closed");
            }
            var future = http.sendAsync(httpRequest, BoundedBodySubscriber.handler(MAX_RESPONSE_BYTES));
            HttpResponse<byte[]> response;
            try {
                response = future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return failed(request, start, Answer.Failed.Reason.TIMEOUT, deadlinePassed(request, start));
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return failed(request, start, Answer.Failed.Reason.ERROR, "interrupted");
            } catch (ExecutionException e) {
                var cause = e.getCause();
                if (closed.get()) {
                    // close() aborted the exchange (HttpClient.shutdownNow()).
                    return failed(request, start, Answer.Failed.Reason.ERROR,
                            "TypeSafe decision model was closed while the request was in flight");
                }
                if (cause instanceof HttpTimeoutException
                        && (!(cause instanceof HttpConnectTimeoutException)
                            || deadlineBoundTheConnect(remaining, deadline))) {
                    return failed(request, start, Answer.Failed.Reason.TIMEOUT, deadlinePassed(request, start));
                }
                if (cause instanceof BoundedBodySubscriber.TooLargeException) {
                    return failed(request, start, Answer.Failed.Reason.UNPARSEABLE, cause.getMessage());
                }
                var detail = "connection failed on attempt " + (attempt + 1) + " of " + (maxRetries + 1)
                        + ": " + cause;
                if (cause instanceof IOException && attempt < maxRetries) {
                    var wait = backoffMillis(attempt);
                    if (!sleepWithin(wait, deadline)) {
                        return stopRetrying(request, start, Answer.Failed.Reason.ERROR, detail, wait);
                    }
                    logger.debug("TypeSafe {}; retry {} of {} in {} ms", detail, attempt + 1, maxRetries, wait);
                    continue;
                }
                return failed(request, start, Answer.Failed.Reason.ERROR, detail);
            }
            var status = response.statusCode();
            if (status == 200) {
                var reply = TypesafeWire.decodeReply(request, response.body());
                var answeredBy = reply.model() != null ? reply.model() : model;
                return new DecisionResult(answeredBy, reply.answers(), reply.usage(), elapsed(start));
            }
            var detail = "HTTP " + status + " " + TypesafeWire.errorMessage(response.body())
                    + requestId(response.headers());
            if (status == 401 || status == 403) {
                markUnavailable(detail);
                logger.warn("TypeSafe rejected the API key: {}", detail);
                return failed(request, start, Answer.Failed.Reason.ERROR, detail);
            }
            var reason = status == 429 || status == 529
                    ? Answer.Failed.Reason.CAPACITY : Answer.Failed.Reason.ERROR;
            if (!retryable(status) || attempt >= maxRetries) {
                return failed(request, start, reason, detail);
            }
            var wait = retryAfterMillis(response.headers()).orElse(backoffMillis(attempt));
            if (!sleepWithin(wait, deadline)) {
                return stopRetrying(request, start, reason, detail, wait);
            }
            logger.debug("TypeSafe {}; retry {} of {} after {} ms", detail, attempt + 1, maxRetries, wait);
        }
    }

    /**
     * Whether an {@link HttpConnectTimeoutException} means the deadline passed.
     * The JDK reports it both when {@code connectTimeout} fires and when the
     * request timer (set to the time left, {@code remaining}) fires before the
     * connection is up. Only the first is a connection error worth a retry: when
     * the connect bound is not shorter than the time the attempt had, or the
     * deadline has gone, the request timer fired and the answer is TIMEOUT, as
     * for any other deadline.
     */
    private boolean deadlineBoundTheConnect(long remaining, long deadline) {
        return connectTimeout.compareTo(Duration.ofNanos(remaining)) >= 0 || deadline - System.nanoTime() <= 0;
    }

    private static String deadlinePassed(DecisionRequest request, long start) {
        return "no reply before the " + request.timeout().toMillis() + " ms deadline ("
                + elapsed(start).toMillis() + " ms elapsed)";
    }

    private DecisionResult stopRetrying(DecisionRequest request, long start, Answer.Failed.Reason reason,
                                        String detail, long wait) {
        if (Thread.currentThread().isInterrupted()) {
            return failed(request, start, Answer.Failed.Reason.ERROR, detail + "; interrupted while waiting to retry");
        }
        return failed(request, start, reason, detail + "; the " + wait
                + " ms wait before a retry would pass the deadline");
    }

    /**
     * Sleep {@code millis} if that ends before {@code deadline}; false when it
     * would not, or on interrupt. The comparison is against the time left, never
     * {@code now + wait}: a server-named wait near {@link Long#MAX_VALUE} would
     * overflow that sum, read as already past, and sleep for ever.
     */
    static boolean sleepWithin(long millis, long deadline) {
        var remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis >= remainingMillis) {
            return false;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean retryable(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    /** Exponential backoff with up to 25% subtracted at random. */
    static long backoffMillis(int attempt) {
        var base = Math.min(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS << Math.min(attempt, 20));
        var jitter = (long) (base * 0.25 * ThreadLocalRandom.current().nextDouble());
        return base - jitter;
    }

    /**
     * The server's requested wait: {@code retry-after-ms} (milliseconds), else
     * {@code Retry-After} (delta seconds or an HTTP date). Empty when neither is
     * present or parseable; a negative wait reads as {@code 0}.
     */
    static OptionalLong retryAfterMillis(HttpHeaders headers) {
        var millis = headers.firstValue("retry-after-ms");
        if (millis.isPresent()) {
            try {
                var value = Double.parseDouble(millis.get().strip());
                if (Double.isFinite(value)) {
                    return OptionalLong.of(Math.max(0L, (long) Math.ceil(value)));
                }
            } catch (NumberFormatException e) {
                logger.debug("Ignoring unparseable retry-after-ms '{}'", millis.get(), e);
            }
        }
        var after = headers.firstValue("retry-after");
        if (after.isEmpty()) {
            return OptionalLong.empty();
        }
        var text = after.get().strip();
        try {
            var seconds = Double.parseDouble(text);
            if (Double.isFinite(seconds)) {
                return OptionalLong.of(Math.max(0L, (long) Math.ceil(seconds * 1000.0)));
            }
        } catch (NumberFormatException e) {
            logger.trace("Retry-After '{}' is not a number; trying an HTTP date", text, e);
        }
        try {
            var at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME);
            var wait = Duration.between(ZonedDateTime.now(at.getZone()), at).toMillis();
            return OptionalLong.of(Math.max(0L, wait));
        } catch (DateTimeParseException e) {
            logger.debug("Ignoring unparseable Retry-After '{}'", text, e);
            return OptionalLong.empty();
        }
    }

    private static String requestId(HttpHeaders headers) {
        return headers.firstValue("x-typesafe-request-id").map(id -> " (request " + id + ")").orElse("");
    }

    private DecisionResult failed(DecisionRequest request, long start, Answer.Failed.Reason reason, String detail) {
        var answers = new LinkedHashMap<String, Answer>();
        for (var id : request.questions().keySet()) {
            answers.put(id, new Answer.Failed(id, reason, detail));
        }
        return new DecisionResult(model, answers, Optional.empty(), elapsed(start));
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /**
     * Closes the owned {@link HttpClient}, if one was created. Idempotent. The
     * exchanges in flight are aborted ({@link HttpClient#shutdownNow()}), not
     * waited for: {@link HttpClient#close()} alone is an orderly shutdown that
     * would block the caller (e.g. {@code DecisionModelResolver.reset()}) until
     * the slowest request reached its own deadline. The aborted questions fail
     * with {@link Answer.Failed.Reason#ERROR}. The wait for the client to
     * terminate is bounded by {@value #CLOSE_WAIT_SECONDS} s. The shared
     * availability verdict is left to the other instances of the same
     * configuration.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            HttpClient owned;
            synchronized (clientLock) {
                owned = client;
                client = null;
            }
            if (owned != null) {
                owned.shutdownNow();
                try {
                    if (!owned.awaitTermination(Duration.ofSeconds(CLOSE_WAIT_SECONDS))) {
                        logger.debug("TypeSafe HttpClient did not terminate within {} s of shutdownNow()",
                                CLOSE_WAIT_SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("Interrupted waiting for the TypeSafe HttpClient to terminate", e);
                }
            }
        }
    }

    private static URI validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("base URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base URL '" + baseUrl + "' is not a URI: " + e.getMessage(), e);
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("base URL '" + baseUrl + "' must be an absolute http(s) URL");
        }
        if (scheme.equals("http") && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("base URL '" + baseUrl
                    + "' uses plain http to a non-loopback host; the API key would cross the network unencrypted");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("base URL '" + baseUrl + "' must not carry user info, query or fragment");
        }
        var path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("base URL '" + baseUrl + "' must not carry a path; the adapter adds /v1/...");
        }
        return URI.create(scheme + "://" + uri.getRawAuthority() + "/");
    }

    private static boolean isLoopback(String host) {
        var h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    private static void validateModel(String model) {
        if (model == null || !MODEL_ID.matcher(model).matches()) {
            throw new IllegalArgumentException("model id must match " + MODEL_ID.pattern() + ", got '" + model + "'");
        }
    }

    private static void validateKey(String key) {
        if (key.isBlank() || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("API key must be non-blank printable ASCII without spaces");
        }
    }

    /** Explicit configuration. {@link #build()} throws on invalid input. */
    public static final class Builder {
        private final boolean strict;
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private String model = DEFAULT_MODEL;
        private int priority = DEFAULT_PRIORITY;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;
        private Duration connectTimeout = Duration.ofSeconds(PROBE_TIMEOUT_SECONDS);
        private Duration availableTtl = Duration.ofSeconds(DEFAULT_AVAILABLE_TTL_SECONDS);
        private Duration unavailableTtl = Duration.ofSeconds(DEFAULT_UNAVAILABLE_TTL_SECONDS);
        private LongSupplier nanoClock = SYSTEM_CLOCK;

        private Builder(boolean strict) {
            this.strict = strict;
        }

        /** The API key; {@code null} leaves the model unavailable. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The base URL, scheme and authority only; default {@value #DEFAULT_BASE_URL}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** The model id; default the pinned {@value #DEFAULT_MODEL}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Selection priority; default {@value #DEFAULT_PRIORITY}. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Retries after the first attempt, {@code 0..10}; default {@value #DEFAULT_MAX_RETRIES}. */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0 || maxRetries > 10) {
                throw new IllegalArgumentException("maxRetries must be in 0..10, got " + maxRetries);
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** Requests in flight, {@code >= 1}; default {@value #DEFAULT_MAX_CONCURRENCY}. */
        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, got " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /** TCP connect bound; positive. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        /** How long a reachable verdict is trusted; positive. */
        public Builder availableTtl(Duration ttl) {
            this.availableTtl = requirePositive(ttl, "availableTtl");
            return this;
        }

        /** How long an unreachable verdict is trusted; positive. */
        public Builder unavailableTtl(Duration ttl) {
            this.unavailableTtl = requirePositive(ttl, "unavailableTtl");
            return this;
        }

        /** Test hook: the clock the availability TTLs are measured on. */
        Builder nanoClock(LongSupplier nanoClock) {
            this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
            return this;
        }

        /** A new instance; it creates and owns its {@link HttpClient} on its first request. */
        public TypesafeDecisionModel build() {
            return new TypesafeDecisionModel(this);
        }

        private static Duration requirePositive(Duration value, String what) {
            Objects.requireNonNull(value, what);
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(what + " must be positive, got " + value);
            }
            return value;
        }
    }
}
