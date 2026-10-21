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
package org.atmosphere.ai.sk;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelineNextSyncPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Builds a {@link OpenAIAsyncClient} that works against any OpenAI-compatible
 * endpoint — not just OpenAI proper or Azure OpenAI.
 *
 * <p>SK 1.4.0 wraps the Azure OpenAI SDK (azure-ai-openai 1.0.0-beta.12). That
 * SDK decides between "Azure" and "non-Azure" URL shapes with the heuristic
 * {@code endpoint.startsWith("https://api.openai.com/v1")}. Any other
 * OpenAI-compat provider — Gemini's {@code generativelanguage.googleapis.com/v1beta/openai},
 * Together, Groq, Ollama, self-hosted proxies — gets the Azure shape
 * ({@code {endpoint}/openai/deployments/{model}/chat/completions}) and 404s
 * against the real OpenAI-style path ({@code {endpoint}/chat/completions}).</p>
 *
 * <p>{@link #forEndpoint(String, String)} works around this by declaring
 * {@code https://api.openai.com/v1} to the SDK (so the non-Azure path is
 * selected) and installing an {@link HttpPipelinePolicy} that rewrites every
 * outgoing URL's scheme/host/path-prefix back to the real endpoint before the
 * request goes out.</p>
 *
 * <p>When the configured endpoint is null or already an OpenAI URL, the
 * rewrite is skipped — the SDK handles those correctly on its own.</p>
 */
public final class SemanticKernelOpenAiClientFactory {

    /** Sentinel the Azure SDK recognizes as "non-Azure OpenAI". */
    static final String OPENAI_SENTINEL_BASE = "https://api.openai.com/v1";

    /**
     * Opt-in that lets an API key go over plain {@code http} to a host that is
     * not loopback. Read per application by
     * {@link AtmosphereSemanticKernelClientAutoConfiguration}; code that calls
     * {@link #forEndpoint(String, String, boolean)} itself passes it.
     */
    public static final String ALLOW_CLEARTEXT_API_KEY_PROPERTY =
            "atmosphere.semantic-kernel.allow-cleartext-api-key";

    /**
     * Credential sent when the caller has none. The Azure SDK requires a
     * non-blank key; a keyless server (Ollama, vLLM, LM Studio) ignores it,
     * and it is public, so it is no secret.
     */
    static final String NO_API_KEY = "not-needed-for-local";

    private static final Logger logger = LoggerFactory.getLogger(SemanticKernelOpenAiClientFactory.class);

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private SemanticKernelOpenAiClientFactory() {
    }

    /**
     * Build an async client for {@code endpoint} authenticated with {@code apiKey},
     * refusing to send the key over plain {@code http} to a host that is not
     * loopback. Same as {@link #forEndpoint(String, String, boolean)} with
     * {@code allowCleartextApiKey = false}.
     *
     * @param endpoint the OpenAI-compatible base URL; null or blank selects OpenAI
     * @param apiKey   the API key; null or blank for a keyless server
     * @return the client
     * @throws IllegalArgumentException when a key would go over plain {@code http}
     *                                  to a host that is not loopback
     */
    public static OpenAIAsyncClient forEndpoint(String endpoint, String apiKey) {
        return forEndpoint(endpoint, apiKey, false);
    }

    /**
     * Build an async client for {@code endpoint} authenticated with {@code apiKey}.
     * Null or OpenAI endpoints use the SDK's built-in non-Azure path; every
     * other endpoint gets a URL-rewrite policy so the SDK's Azure bias is bypassed.
     *
     * <p>An API key goes over plain {@code http} only to a loopback host
     * ({@code localhost}, a {@code 127.0.0.0/8} or {@code ::1} literal), decided
     * from the URL alone with no DNS lookup. To any other {@code http} host the
     * key would cross the network in cleartext, so the call is refused unless
     * {@code allowCleartextApiKey} is set, in which case it proceeds with a
     * warning. A keyless call (null or blank {@code apiKey}) carries no secret
     * and goes to any host.</p>
     *
     * @param endpoint             the OpenAI-compatible base URL; null or blank selects OpenAI
     * @param apiKey               the API key; null or blank for a keyless server
     * @param allowCleartextApiKey whether a key may go over plain {@code http}
     *                             to a host that is not loopback
     * @return the client
     * @throws IllegalArgumentException when a key would go over plain {@code http}
     *                                  to a host that is not loopback and
     *                                  {@code allowCleartextApiKey} is not set
     */
    public static OpenAIAsyncClient forEndpoint(String endpoint, String apiKey, boolean allowCleartextApiKey) {
        String resolved = endpoint == null || endpoint.isBlank() ? OPENAI_SENTINEL_BASE : endpoint;
        boolean keyless = apiKey == null || apiKey.isBlank();

        if (!keyless && sendsKeyInCleartextToRemoteHost(resolved)) {
            if (!allowCleartextApiKey) {
                throw new IllegalArgumentException("Refusing to send the API key to '" + resolved + "': "
                        + (isUri(resolved) ? "it is plain http to a host that is not loopback"
                                : "it is not a URI, so it cannot be shown to be https or loopback")
                        + ", and the key would cross the network unencrypted. Use an https endpoint,"
                        + " or accept the risk with "
                        + ALLOW_CLEARTEXT_API_KEY_PROPERTY + "=true (forEndpoint(endpoint, apiKey, true)"
                        + " when building the client yourself).");
            }
            logger.warn("Semantic Kernel sends the API key in CLEARTEXT over plain http to '{}' ({}=true);"
                    + " anyone on the network path can read it. Use an https endpoint in production.",
                    resolved, ALLOW_CLEARTEXT_API_KEY_PROPERTY);
        }
        var credential = new KeyCredential(keyless ? NO_API_KEY : apiKey);

        if (isOpenAiNative(resolved)) {
            return new OpenAIClientBuilder()
                    .credential(credential)
                    .endpoint(resolved)
                    .buildAsyncClient();
        }

        return new OpenAIClientBuilder()
                .credential(credential)
                .endpoint(OPENAI_SENTINEL_BASE)
                .addPolicy(new RewriteUrlPolicy(resolved))
                .buildAsyncClient();
    }

    /**
     * Whether {@code endpoint} is plain {@code http} to a host that is not
     * loopback. An endpoint that is not a URI, or an {@code http} URL with no
     * host, counts as remote: the key's safety cannot be shown, so it fails
     * closed.
     */
    static boolean sendsKeyInCleartextToRemoteHost(String endpoint) {
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
        return uri.getHost() == null || !isLoopbackHost(uri.getHost());
    }

    private static boolean isUri(String endpoint) {
        try {
            URI.create(endpoint.strip());
            return true;
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
    }

    /**
     * Whether {@code host} is loopback, read from the name alone: the name
     * {@code localhost}, or an IPv4 or IPv6 literal in {@code 127.0.0.0/8} or
     * {@code ::1}. Any other name is not loopback; no DNS lookup is made.
     *
     * @param host a URI host, IPv6 literals in brackets or not
     * @return whether the host is loopback
     */
    static boolean isLoopbackHost(String host) {
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

    static boolean isOpenAiNative(String endpoint) {
        return endpoint != null && endpoint.startsWith(OPENAI_SENTINEL_BASE);
    }

    /**
     * Rewrite any URL whose prefix matches the OpenAI sentinel to the real
     * base URL. Kept package-private so tests can invoke the rewrite logic
     * directly without spinning up a full pipeline.
     */
    static String rewriteUrl(String url, String realBaseUrl) {
        if (url == null || !url.startsWith(OPENAI_SENTINEL_BASE)) {
            return url;
        }
        String suffix = url.substring(OPENAI_SENTINEL_BASE.length());
        String normalizedBase = realBaseUrl.endsWith("/")
                ? realBaseUrl.substring(0, realBaseUrl.length() - 1)
                : realBaseUrl;
        return normalizedBase + suffix;
    }

    /**
     * HTTP pipeline policy that swaps {@code https://api.openai.com/v1} for
     * the configured real base URL on every outgoing request. Runs in the
     * pipeline, so it sees the URL after the SDK has appended path segments
     * ({@code /chat/completions}, {@code /embeddings}, etc.), and per retry,
     * after the key-credential policy (see {@link #getPipelinePosition()}).
     */
    static final class RewriteUrlPolicy implements HttpPipelinePolicy {
        private final String realBaseUrl;

        RewriteUrlPolicy(String realBaseUrl) {
            this.realBaseUrl = realBaseUrl;
        }

        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext ctx, HttpPipelineNextPolicy next) {
            applyRewrite(ctx);
            return next.process();
        }

        @Override
        public HttpResponse processSync(HttpPipelineCallContext ctx, HttpPipelineNextSyncPolicy next) {
            applyRewrite(ctx);
            return next.processSync();
        }

        /**
         * Per-retry, so the rewrite runs after the SDK's key-credential policy:
         * that policy refuses a key on an {@code http} URL ("Key credentials
         * require HTTPS"), and before the rewrite the URL is still the
         * {@code https} sentinel. A per-call rewrite failed every call to a
         * plain-HTTP endpoint (the local default {@code http://localhost:11434/v1}).
         * The retry policy hands each attempt a copy of the original request,
         * so every attempt is rewritten once.
         */
        @Override
        public HttpPipelinePosition getPipelinePosition() {
            return HttpPipelinePosition.PER_RETRY;
        }

        private void applyRewrite(HttpPipelineCallContext ctx) {
            var request = ctx.getHttpRequest();
            URL current = request.getUrl();
            if (current == null) {
                return;
            }
            String rewritten = rewriteUrl(current.toString(), realBaseUrl);
            if (rewritten != null && !rewritten.equals(current.toString())) {
                try {
                    request.setUrl(URI.create(rewritten).toURL());
                } catch (IllegalArgumentException | MalformedURLException invalid) {
                    // Fall back to the string setter; the SDK parses it later.
                    request.setUrl(rewritten);
                }
            }
        }
    }
}
