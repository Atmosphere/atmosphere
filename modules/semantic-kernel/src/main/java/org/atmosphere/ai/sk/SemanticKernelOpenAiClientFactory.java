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
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

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

    private SemanticKernelOpenAiClientFactory() {
    }

    /**
     * Build an async client for {@code endpoint} authenticated with {@code apiKey}.
     * Null or OpenAI endpoints use the SDK's built-in non-Azure path; every
     * other endpoint gets a URL-rewrite policy so the SDK's Azure bias is bypassed.
     */
    public static OpenAIAsyncClient forEndpoint(String endpoint, String apiKey) {
        String resolved = endpoint == null || endpoint.isBlank() ? OPENAI_SENTINEL_BASE : endpoint;

        if (isOpenAiNative(resolved)) {
            return new OpenAIClientBuilder()
                    .credential(new KeyCredential(apiKey))
                    .endpoint(resolved)
                    .buildAsyncClient();
        }

        return new OpenAIClientBuilder()
                .credential(new KeyCredential(apiKey))
                .endpoint(OPENAI_SENTINEL_BASE)
                .addPolicy(new RewriteUrlPolicy(resolved))
                .buildAsyncClient();
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
     * the configured real base URL on every outgoing request. Runs at the
     * per-call position so it sees the URL after the SDK has appended path
     * segments ({@code /chat/completions}, {@code /embeddings}, etc.).
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

        @Override
        public HttpPipelinePosition getPipelinePosition() {
            return HttpPipelinePosition.PER_CALL;
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
