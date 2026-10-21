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
package org.atmosphere.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Where an API key may go over plain {@code http}: the one rule every runtime
 * applies to the base URL Atmosphere sends a key to.
 *
 * <ul>
 *   <li>{@code https}: the key is sent.</li>
 *   <li>{@code http} to a loopback host ({@code localhost} in any case, an IPv4
 *       literal in {@code 127.0.0.0/8}, the IPv6 literal {@code ::1} or an
 *       IPv4-mapped {@code ::ffff:127.x.y.z}): the key is sent. Loopback is
 *       read from the URL alone, with no DNS lookup, so a name that resolves to
 *       {@code 127.0.0.1} (or {@code host.docker.internal}) is not loopback.</li>
 *   <li>{@code http} to any other host, or an endpoint that is not a URI: the
 *       key would cross the network in cleartext, so the call is
 *       {@linkplain Verdict#REFUSE refused}, unless the application opts in
 *       with {@value #ALLOW_PROPERTY}{@code =true} (environment variable
 *       {@value #ALLOW_ENV}), in which case it is
 *       {@linkplain Verdict#SEND_IN_CLEARTEXT sent} with a warning.</li>
 *   <li>A keyless call (no key, or the public placeholder {@value #NO_API_KEY}
 *       a local server ignores) carries no secret and goes to any host.</li>
 * </ul>
 *
 * <p>A runtime whose endpoint is known at startup refuses there
 * ({@link #requireAllowed}: the application fails to start); a client built
 * per request refuses the call ({@link #verdict} and {@link #refusal}). Either
 * way the key is never sent, and the refusal is logged at {@code ERROR}.</p>
 *
 * <p>The opt-in is read per application: by the built-in, Anthropic and Cohere
 * clients and the built-in embedding runtime from their
 * {@link org.atmosphere.ai.AiEnvironment}
 * ({@link org.atmosphere.ai.AiEnvironment#resolveAllowCleartextApiKey()}), and
 * by the Spring auto-configurations from that application's Spring
 * {@code Environment}. Only {@code true} opts in ({@link #optedIn}).</p>
 */
public final class CleartextApiKeyPolicy {

    /**
     * Opt-in that lets an API key go over plain {@code http} to a host that is
     * not loopback, for every runtime of the application.
     */
    public static final String ALLOW_PROPERTY = "atmosphere.ai.allow-cleartext-api-key";

    /** The environment variable that also sets {@link #ALLOW_PROPERTY}. */
    public static final String ALLOW_ENV = "ATMOSPHERE_AI_ALLOW_CLEARTEXT_API_KEY";

    /**
     * The credential a client sends to a keyless local server (Ollama, vLLM,
     * LM Studio) that requires a non-blank one; public, so it is no secret.
     */
    public static final String NO_API_KEY = "not-needed-for-local";

    /** What to do with a key bound for an endpoint. */
    public enum Verdict {
        /** Send it: the endpoint is https or loopback, or there is no key. */
        SEND,
        /** Send it over plain http to a host that is not loopback: the application opted in. */
        SEND_IN_CLEARTEXT,
        /** Do not send it: it would go over plain http to a host that is not loopback. */
        REFUSE
    }

    private static final Logger logger = LoggerFactory.getLogger(CleartextApiKeyPolicy.class);

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private CleartextApiKeyPolicy() {
    }

    /**
     * What to do with {@code apiKey} bound for {@code endpoint}.
     *
     * @param endpoint             the base URL the key goes to
     * @param apiKey               the key; null, blank or {@value #NO_API_KEY} for a keyless call
     * @param allowCleartextApiKey whether the application opted in ({@value #ALLOW_PROPERTY})
     * @return the verdict, never {@code null}
     */
    public static Verdict verdict(String endpoint, String apiKey, boolean allowCleartextApiKey) {
        if (!hasKey(apiKey) || !sendsKeyInCleartext(endpoint)) {
            return Verdict.SEND;
        }
        return allowCleartextApiKey ? Verdict.SEND_IN_CLEARTEXT : Verdict.REFUSE;
    }

    /**
     * Refuse at startup: when the verdict is {@link Verdict#REFUSE} log the
     * refusal at {@code ERROR} and throw it; when the application opted in, log
     * the cleartext send at {@code WARN}.
     *
     * @param log                  the caller's logger, so the log names the component
     * @param component            the runtime or client, e.g. {@code "LangChain4j"}
     * @param endpoint             the base URL the key goes to
     * @param apiKey               the key, before any placeholder is put in its place
     * @param allowCleartextApiKey whether the application opted in
     * @throws IllegalArgumentException when the key would go over plain http to
     *                                  a host that is not loopback and the
     *                                  application did not opt in
     */
    public static void requireAllowed(Logger log, String component, String endpoint, String apiKey,
                                      boolean allowCleartextApiKey) {
        switch (verdict(endpoint, apiKey, allowCleartextApiKey)) {
            case SEND -> {
                // https, loopback, or no key: nothing to say.
            }
            case SEND_IN_CLEARTEXT -> warnCleartext(log, component, endpoint);
            case REFUSE -> {
                var message = refusal(component, endpoint);
                log.error(message);
                throw new IllegalArgumentException(message);
            }
        }
    }

    /**
     * The refusal: names the component, the endpoint, that the key would cross
     * the network in cleartext, and the opt-in. It never carries the key.
     *
     * @param component the runtime or client
     * @param endpoint  the base URL the key would go to
     * @return the message
     */
    public static String refusal(String component, String endpoint) {
        return component + " refuses to send the API key to '" + endpoint + "': "
                + (isUri(endpoint) ? "it is plain http to a host that is not loopback"
                        : "it is not a URI, so it cannot be shown to be https or loopback")
                + ", so the key would cross the network in cleartext. Use an https endpoint, or"
                + " accept the risk with " + ALLOW_PROPERTY + "=true (environment variable "
                + ALLOW_ENV + "=true).";
    }

    /**
     * Log, at {@code WARN}, that the application opted in and the key goes to
     * {@code endpoint} in cleartext.
     *
     * @param log       the caller's logger
     * @param component the runtime or client
     * @param endpoint  the base URL the key goes to
     */
    public static void warnCleartext(Logger log, String component, String endpoint) {
        log.warn("{} sends the API key in CLEARTEXT over plain http to '{}' ({}=true); anyone on the"
                + " network path can read it. Use an https endpoint in production.",
                component, endpoint, ALLOW_PROPERTY);
    }

    /**
     * Whether {@code raw}, a value of {@value #ALLOW_PROPERTY}, opts in: only
     * {@code true} (any case, surrounding blanks ignored) does; a blank,
     * {@code false} or any other value keeps the refusal.
     *
     * @param raw the configured value, may be {@code null}
     * @return whether it opts in
     */
    public static boolean optedIn(String raw) {
        return raw != null && Boolean.parseBoolean(raw.strip());
    }

    /**
     * Whether {@code apiKey} is a secret: not null, not blank, and not the
     * public placeholder {@value #NO_API_KEY}.
     *
     * @param apiKey the key
     * @return whether a secret would be sent
     */
    public static boolean hasKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !NO_API_KEY.equals(apiKey.strip());
    }

    /**
     * Whether a key sent to {@code endpoint} would cross the network in
     * cleartext: the endpoint is plain {@code http} to a host that is not
     * loopback. An endpoint that is not a URI, or an {@code http} URL with no
     * host, counts as such: the key's safety cannot be shown, so it fails
     * closed. A null or blank endpoint is not: each runtime then uses its own
     * {@code https} default.
     *
     * @param endpoint the base URL
     * @return whether the key would go in cleartext to a host that is not loopback
     */
    public static boolean sendsKeyInCleartext(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(endpoint.strip());
        } catch (IllegalArgumentException notAUri) {
            logger.trace("Endpoint '{}' is not a URI; treated as a remote cleartext host", endpoint, notAUri);
            return true;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        return !isLoopbackHost(uri.getHost());
    }

    /**
     * Whether {@code host} is loopback, read from the name alone: the name
     * {@code localhost} (any case), an IPv4 literal in {@code 127.0.0.0/8}, or
     * an IPv6 literal that is loopback ({@code ::1}, or an IPv4-mapped
     * {@code ::ffff:127.x.y.z}). Any other name is not loopback; no DNS lookup
     * is made.
     *
     * @param host a URI host, IPv6 literals in brackets or not
     * @return whether the host is loopback
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        var name = host.strip();
        if (name.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        if (IPV4_LITERAL.matcher(name).matches()) {
            var octets = name.split("\\.");
            return Integer.parseInt(octets[0]) == 127
                    && Arrays.stream(octets).allMatch(octet -> Integer.parseInt(octet) <= 255);
        }
        if (!name.contains(":")) {
            return false;
        }
        try {
            // In brackets InetAddress expects an IPv6 literal: it parses it, or
            // throws, and never falls back to a lookup.
            return InetAddress.getByName("[" + name + "]").isLoopbackAddress();
        } catch (UnknownHostException notALiteral) {
            logger.trace("Host '{}' is not an IPv6 literal; not loopback", name, notALiteral);
            return false;
        }
    }

    private static boolean isUri(String endpoint) {
        try {
            URI.create(endpoint.strip());
            return true;
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
    }
}
