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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared HTTP/SSE plumbing for direct-HTTP streaming LLM clients that talk a
 * {@code data: }-framed SSE protocol over the JDK {@link HttpClient}. Extracted
 * from the near-identical {@code AnthropicMessagesClient} and
 * {@code CohereChatClient} so both share their connection, reserved-header
 * filtering, response-snippet, line-loop, and tool-schema emission with
 * byte-identical wire behaviour.
 *
 * <p>This base owns <strong>only</strong> the duplicated transport surface. All
 * provider-specific concerns — request-body builders, SSE-event → model
 * mapping, the tool envelope wrapping, the auth header name, the endpoint path,
 * Anthropic's {@code anthropic-version} — stay in the concrete subclasses. The
 * only abstract hook is {@link #providerName()} used to compose the non-2xx
 * error string.</p>
 *
 * <p>Subclasses pass their resolved configuration via an immutable
 * {@link SseClientConfig} carrier to the protected super-constructor; each
 * subclass keeps its own concrete {@code Builder}.</p>
 */
public abstract class AbstractSseLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSseLlmClient.class);

    /** SSE line prefix carrying a JSON event payload. */
    protected static final String DATA_PREFIX = "data: ";

    /** Shared JSON mapper using the same {@link JsonMapper} config the clients use. */
    protected final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Normalised base URL (no trailing slash). */
    protected final String baseUrl;
    /** Provider API key; may be null/blank when injected via a custom header. */
    protected final String apiKey;
    /** Transport client; never null (defaulted by the carrier when unset). */
    protected final HttpClient httpClient;
    /** Per-request timeout applied to the {@link HttpRequest}. */
    protected final Duration timeout;
    /** Default {@code max_tokens} ceiling forwarded on the request body. */
    protected final int maxTokens;
    /** Caller-supplied custom headers (observability / proxy / tenant metadata). */
    protected final Map<String, String> customHeaders;

    /**
     * Immutable configuration carrier passed from each subclass Builder into
     * {@link #AbstractSseLlmClient(SseClientConfig)}. Keeps the base free of
     * self-typed generic Builder gymnastics while letting each subclass retain
     * its own concrete Builder (and provider-only fields such as Anthropic's
     * {@code anthropicVersion}, which stays in that subclass's Builder).
     *
     * @param baseUrl       raw base URL; trailing slash is trimmed by the constructor
     * @param apiKey        provider API key (may be null/blank)
     * @param httpClient    transport client; null means "build a 30s-connect default"
     * @param timeout       per-request timeout
     * @param maxTokens     default {@code max_tokens}
     * @param customHeaders caller custom headers (copied defensively)
     */
    public record SseClientConfig(
            String baseUrl,
            String apiKey,
            HttpClient httpClient,
            Duration timeout,
            int maxTokens,
            Map<String, String> customHeaders) {
    }

    /**
     * Initialise the shared transport state from an immutable carrier.
     * Reproduces the field-setup the two clients performed inline: trailing
     * slash trim on {@code baseUrl}, a 30s-connect default {@link HttpClient}
     * when none is supplied, and a defensive copy of the custom headers.
     */
    protected AbstractSseLlmClient(SseClientConfig config) {
        this.baseUrl = config.baseUrl().endsWith("/")
                ? config.baseUrl().substring(0, config.baseUrl().length() - 1)
                : config.baseUrl();
        this.apiKey = config.apiKey();
        this.httpClient = config.httpClient() != null ? config.httpClient()
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.timeout = config.timeout();
        this.maxTokens = config.maxTokens();
        this.customHeaders = Map.copyOf(config.customHeaders());
    }

    /** Visible for tests. */
    public Map<String, String> customHeaders() {
        return customHeaders;
    }

    /**
     * Provider display name used to compose the non-2xx error string
     * ({@code "<provider> API returned <code>: <snippet>"}). Returns
     * {@code "Anthropic"} / {@code "Cohere"} in the shipped subclasses.
     */
    protected abstract String providerName();

    /**
     * Send one SSE round: POST the request, and on a 2xx response stream the
     * {@code data: }-framed body line-by-line, dispatching each parsed JSON
     * event to {@code onEvent}. On a non-2xx response, surfaces
     * {@code session.error(providerName() + " API returned " + code + ": " + snippet)}.
     * On a transport / IO exception, surfaces {@code session.error(e)}.
     *
     * <p>Byte-identical to the per-client {@code runRound} + the SSE
     * {@code readLine} scaffolding: cancel check first, skip non-{@code data:}
     * lines, trim the payload, drop empty payloads, parse with {@code MAPPER}
     * (debug-skip on {@link RuntimeException}), then invoke {@code onEvent}.</p>
     *
     * @return {@code true} when the round completed by reading the stream to
     *         end-of-input; {@code false} when it errored or was cancelled
     *         mid-stream (in which case the session has already been notified
     *         for the error paths; the cancel path leaves the session untouched
     *         exactly as the originals did)
     */
    protected final boolean runRound(HttpRequest httpRequest,
                                     StreamingSession session,
                                     AtomicBoolean cancelled,
                                     Consumer<JsonNode> onEvent) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            session.error(e);
            return false;
        }
        if (response.statusCode() / 100 != 2) {
            var bodySnippet = readSnippet(response.body());
            session.error(new RuntimeException(providerName() + " API returned "
                    + response.statusCode() + ": " + bodySnippet));
            return false;
        }
        try (var body = response.body();
             var reader = new BufferedReader(
                     new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.get()) {
                    return false;
                }
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                var payload = line.substring(DATA_PREFIX.length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                JsonNode event;
                try {
                    event = MAPPER.readTree(payload);
                } catch (RuntimeException e) {
                    logger.debug("Skipping unparseable SSE payload: {}", payload, e);
                    continue;
                }
                onEvent.accept(event);
            }
            return true;
        } catch (Exception e) {
            session.error(e);
            return false;
        }
    }

    /**
     * Apply caller custom headers to the request, skipping any header whose
     * name (case-insensitively) matches a reserved protocol header. Null/blank
     * names and null values are dropped. Byte-identical to the per-client
     * filter loop — only the {@code reservedLowercase} set differs per provider
     * (Anthropic adds {@code x-api-key}/{@code anthropic-version}; Cohere adds
     * {@code authorization}).
     *
     * @param builder           the request builder to mutate
     * @param reservedLowercase the reserved header names (compared via
     *                          {@code equalsIgnoreCase})
     */
    protected final void applyReservedFilteredHeaders(HttpRequest.Builder builder,
                                                      Set<String> reservedLowercase) {
        for (var entry : customHeaders.entrySet()) {
            var name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            var reserved = false;
            for (var r : reservedLowercase) {
                if (name.equalsIgnoreCase(r)) {
                    reserved = true;
                    break;
                }
            }
            if (reserved) {
                logger.debug("Skipping reserved {} header {} from customHeaders",
                        providerName(), name);
                continue;
            }
            var value = entry.getValue();
            if (value != null) {
                builder.header(name, value);
            }
        }
    }

    /**
     * Drain a response body into a UTF-8 string capped at 500 characters
     * (appending {@code "..."} when truncated). Returns {@code "<no body>"} for
     * a null stream and {@code "<unreadable: X>"} when the read throws. The
     * stream is closed via try-with-resources. Byte-identical to the
     * per-client {@code readSnippet}.
     */
    protected final String readSnippet(InputStream body) {
        if (body == null) {
            return "<no body>";
        }
        try (body) {
            var bytes = body.readAllBytes();
            var text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        } catch (Exception e) {
            return "<unreadable: " + e.getClass().getSimpleName() + ">";
        }
    }

    /**
     * Build the shared inner JSON-Schema object for a tool: an
     * {@code {"type":"object","properties":{...},"required":[...]}} node where
     * each property carries its {@code type} (defaulting to {@code "string"})
     * and a {@code description} only when non-blank, and {@code required} is
     * emitted only when at least one parameter is required.
     *
     * <p>Each provider wraps this inner object in its own envelope key —
     * Anthropic under {@code input_schema}, Cohere under
     * {@code function.parameters} — so the wrapping stays in the subclass;
     * only this inner schema object is shared. The {@code mapper} is passed in
     * so the node is created with the caller's mapper (it is always the
     * inherited {@link #MAPPER}).</p>
     */
    protected final ObjectNode toolSchemaObjectNode(ToolDefinition def, ObjectMapper mapper) {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        var required = mapper.createArrayNode();
        for (var param : def.parameters()) {
            var prop = properties.putObject(param.name());
            prop.put("type", param.type() != null ? param.type() : "string");
            if (param.description() != null && !param.description().isBlank()) {
                prop.put("description", param.description());
            }
            if (param.required()) {
                required.add(param.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }
}
