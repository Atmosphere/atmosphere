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
package org.atmosphere.mcp.bridge;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * stdio-to-HTTP bridge for MCP. Lets a client that only speaks the stdio
 * transport (Claude Desktop, an {@code .mcpb} bundle, an IDE) talk to an
 * Atmosphere MCP endpoint over Streamable HTTP.
 *
 * <ul>
 *   <li>Each JSON-RPC message read from stdin (one per line) is POSTed on its
 *       own virtual thread, so a long tool call does not hold up a ping, a
 *       cancellation or another call. {@code initialize} is sent before any
 *       other line is read, so every later request carries its session.</li>
 *   <li>An SSE response is relayed event by event as it arrives.</li>
 *   <li>Once the session is initialized, the bridge holds the GET stream open
 *       (reconnecting with backoff) and relays what the server sends on it:
 *       notifications and server-initiated requests such as
 *       {@code elicitation/create} or {@code sampling/createMessage}, whose
 *       answers the client writes to stdin like any other message.</li>
 *   <li>Every message written to stdout is re-serialised as one line of JSON;
 *       a server message that is not JSON is dropped and logged to stderr.</li>
 *   <li>An HTTP error, a timeout or an unreachable server is answered on stdout
 *       with a JSON-RPC error carrying the request's {@code id}; the bridge
 *       keeps running.</li>
 *   <li>At the end of stdin, or when the process is stopped, in-flight requests
 *       get {@link Options#drainTimeout()} to finish, then the session is
 *       ended with {@code DELETE}.</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 *   java -jar atmosphere-mcp-stdio-bridge.jar http://localhost:8083/atmosphere/mcp [--timeout &lt;seconds&gt;]
 * </pre>
 *
 * <p>Environment:</p>
 * <ul>
 *   <li>{@code ATMOSPHERE_MCP_URL}: the endpoint, when no argument gives it;</li>
 *   <li>{@code ATMOSPHERE_MCP_TOKEN}: sent as {@code Authorization: Bearer}.
 *       The token is read from the environment only, never from an argument,
 *       which other users of the machine can read in the process list;</li>
 *   <li>{@code ATMOSPHERE_MCP_TIMEOUT}: seconds to wait for a response's
 *       headers; {@code 0} or unset waits as long as the call takes.</li>
 * </ul>
 *
 * <p>Claude Desktop configuration:</p>
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "atmosphere": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/atmosphere-mcp-stdio-bridge.jar",
 *                "http://localhost:8083/atmosphere/mcp"],
 *       "env": { "ATMOSPHERE_MCP_TOKEN": "..." }
 *     }
 *   }
 * }
 * }</pre>
 */
public final class McpStdioBridge implements AutoCloseable {

    /** Environment variable naming the endpoint when no argument does. */
    public static final String ENV_URL = "ATMOSPHERE_MCP_URL";
    /** Environment variable holding the bearer token. */
    public static final String ENV_TOKEN = "ATMOSPHERE_MCP_TOKEN";
    /** Environment variable holding the request timeout in seconds. */
    public static final String ENV_TIMEOUT = "ATMOSPHERE_MCP_TIMEOUT";

    /** JSON-RPC error code the bridge answers with when the HTTP exchange fails. */
    public static final int TRANSPORT_ERROR = -32000;

    private static final System.Logger logger = System.getLogger(McpStdioBridge.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final int MAX_ERROR_BODY_CHARS = 2_000;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /**
     * How the bridge reaches its endpoint.
     *
     * @param endpoint       the Streamable HTTP MCP endpoint
     * @param bearerToken    sent as {@code Authorization: Bearer}; {@code null} for none
     * @param requestTimeout how long a POST waits for its response headers;
     *                       {@code null} waits as long as the call takes
     * @param connectTimeout how long a connection attempt may take
     * @param drainTimeout   how long in-flight requests may run after stdin ends
     * @param openStream     whether to hold the GET stream open after initialize
     */
    public record Options(URI endpoint, String bearerToken, Duration requestTimeout,
                          Duration connectTimeout, Duration drainTimeout, boolean openStream) {

        public Options {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint is required");
            }
            var scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("endpoint must be an http or https URL: " + endpoint);
            }
            if (bearerToken != null && (bearerToken.isBlank() || bearerToken.indexOf('\r') >= 0
                    || bearerToken.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("bearer token must be one non-blank line");
            }
            if (connectTimeout == null) {
                connectTimeout = Duration.ofSeconds(10);
            }
            if (drainTimeout == null) {
                drainTimeout = Duration.ofSeconds(30);
            }
        }

        /** Like the record's own, with the token redacted. */
        @Override
        public String toString() {
            return "Options[endpoint=" + endpoint + ", bearerToken=" + (bearerToken == null ? "none" : "<redacted>")
                    + ", requestTimeout=" + requestTimeout + ", connectTimeout=" + connectTimeout
                    + ", drainTimeout=" + drainTimeout + ", openStream=" + openStream + "]";
        }

        /** Defaults for {@code endpoint}: no token, no request timeout, GET stream on. */
        public static Options of(String endpoint) {
            return new Options(URI.create(endpoint), null, null, null, null, true);
        }

        public Options withBearerToken(String token) {
            return new Options(endpoint, token, requestTimeout, connectTimeout, drainTimeout, openStream);
        }

        public Options withRequestTimeout(Duration timeout) {
            return new Options(endpoint, bearerToken, timeout, connectTimeout, drainTimeout, openStream);
        }

        public Options withDrainTimeout(Duration timeout) {
            return new Options(endpoint, bearerToken, requestTimeout, connectTimeout, timeout, openStream);
        }

        public Options withOpenStream(boolean open) {
            return new Options(endpoint, bearerToken, requestTimeout, connectTimeout, drainTimeout, open);
        }

        /**
         * Options from the command line and the environment. The first
         * argument not starting with {@code --} is the endpoint, else
         * {@link #ENV_URL}; {@code --timeout <seconds>} overrides
         * {@link #ENV_TIMEOUT}; the token comes from {@link #ENV_TOKEN} only.
         */
        public static Options parse(String[] args, Map<String, String> env) {
            String endpoint = null;
            var timeout = env.get(ENV_TIMEOUT);
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--timeout" -> {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--timeout needs a number of seconds");
                        }
                        timeout = args[++i];
                    }
                    case "--token" -> throw new IllegalArgumentException(
                            "pass the token in " + ENV_TOKEN + ", not on the command line");
                    default -> {
                        if (args[i].startsWith("--") || endpoint != null) {
                            throw new IllegalArgumentException("unexpected argument: " + args[i]);
                        }
                        endpoint = args[i];
                    }
                }
            }
            if (endpoint == null) {
                endpoint = env.get(ENV_URL);
            }
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("no endpoint: pass it as the first argument or set " + ENV_URL);
            }
            Duration requestTimeout = null;
            if (timeout != null && !timeout.isBlank()) {
                long seconds;
                try {
                    seconds = Long.parseLong(timeout.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("timeout must be a whole number of seconds: " + timeout, e);
                }
                if (seconds < 0) {
                    throw new IllegalArgumentException("timeout must not be negative: " + timeout);
                }
                requestTimeout = seconds == 0 ? null : Duration.ofSeconds(seconds);
            }
            var token = env.get(ENV_TOKEN);
            return new Options(URI.create(endpoint.trim()), token == null || token.isBlank() ? null : token.trim(),
                    requestTimeout, null, null, true);
        }
    }

    private final Options options;
    private final InputStream in;
    private final Writer out;
    private final ReentrantLock outLock = new ReentrantLock();
    private final HttpClient http;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch closing = new CountDownLatch(1);
    private final AtomicReference<InputStream> openStreamBody = new AtomicReference<>();
    private volatile String sessionId;
    private volatile String protocolVersion;
    private volatile Thread streamThread;

    /** A bridge for {@code endpointUrl} on the process's stdin and stdout. */
    public McpStdioBridge(String endpointUrl) {
        this(Options.of(endpointUrl), System.in, System.out);
    }

    public McpStdioBridge(Options options, InputStream in, OutputStream out) {
        this.options = options;
        this.in = in;
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder()
                // HTTP/1.1: on a plain http endpoint the client's default h2c
                // upgrade attempt ends a held SSE GET stream right after its
                // first chunk. Concurrent POSTs get a connection each.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("atmosphere-mcp-stdio-bridge: " + e.getMessage());
            System.err.println("Usage: java -jar atmosphere-mcp-stdio-bridge.jar <endpoint-url> [--timeout <seconds>]");
            System.err.println("Environment: " + ENV_URL + ", " + ENV_TOKEN + ", " + ENV_TIMEOUT);
            System.exit(2);
            return;
        }
        var bridge = new McpStdioBridge(options, System.in, System.out);
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::close, "mcp-stdio-bridge-shutdown"));
        try {
            bridge.run();
        } catch (IOException e) {
            logger.log(System.Logger.Level.ERROR, "stdin failed", e);
        } finally {
            bridge.close();
        }
    }

    /** The MCP session the server assigned, or {@code null} before initialize. */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Read stdin until it ends, relaying every line, then {@link #close()}.
     *
     * @throws IOException when stdin cannot be read
     */
    public void run() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = MAPPER.readTree(text);
                } catch (JacksonException e) {
                    emit(error(null, -32700, "Parse error: stdin line is not JSON", null));
                    continue;
                }
                if (isRequest(message, "initialize")) {
                    // Sent before the next line is read: every later request
                    // must carry the session this one opens.
                    relay(text, message);
                } else {
                    try {
                        requests.execute(() -> relay(text, message));
                    } catch (RejectedExecutionException e) {
                        emit(error(message, TRANSPORT_ERROR, "Bridge is shutting down", null));
                    }
                }
            }
        } finally {
            close();
        }
    }

    /**
     * Let in-flight requests finish (up to {@link Options#drainTimeout()}),
     * stop the GET stream and end the session with {@code DELETE}. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        requests.shutdown();
        try {
            if (!requests.awaitTermination(options.drainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                logger.log(System.Logger.Level.WARNING, "In-flight MCP requests still running after {0}; abandoning them",
                        options.drainTimeout());
                requests.shutdownNow();
            }
        } catch (InterruptedException e) {
            requests.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closing.countDown();
        closeQuietly(openStreamBody.getAndSet(null));
        var stream = streamThread;
        if (stream != null) {
            stream.interrupt();
        }
        terminateSession();
        http.shutdownNow();
        try {
            outLock.lock();
            try {
                out.flush();
            } finally {
                outLock.unlock();
            }
        } catch (IOException e) {
            logger.log(System.Logger.Level.DEBUG, "stdout flush on close failed", e);
        }
    }

    // ── POST ────────────────────────────────────────────────────────────

    private void relay(String text, JsonNode message) {
        try {
            var builder = request()
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, " + EVENT_STREAM)
                    .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
            if (options.requestTimeout() != null) {
                builder.timeout(options.requestTimeout());
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                response.headers().firstValue(SESSION_HEADER).ifPresent(id -> sessionId = id);
                var status = response.statusCode();
                if (status == 202 || status == 204) {
                    return;
                }
                if (status < 200 || status >= 300) {
                    var detail = readLimited(body);
                    emit(error(message, TRANSPORT_ERROR, httpErrorMessage(status, response), detail));
                    return;
                }
                if (isEventStream(response)) {
                    var events = new SseEventReader(body);
                    Optional<String> event;
                    while ((event = events.next()).isPresent()) {
                        emitFromServer(event.get(), message);
                    }
                } else {
                    var payload = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    if (!payload.isBlank()) {
                        emitFromServer(payload, message);
                    }
                }
            }
        } catch (HttpTimeoutException e) {
            emit(error(message, TRANSPORT_ERROR, "MCP endpoint did not answer within "
                    + options.requestTimeout() + " (" + ENV_TIMEOUT + ")", null));
        } catch (IOException e) {
            emit(error(message, TRANSPORT_ERROR, "MCP endpoint unreachable: " + e, null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emit(error(message, TRANSPORT_ERROR, "Bridge stopped before the endpoint answered", null));
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.ERROR, "Relaying a message failed", e);
            emit(error(message, TRANSPORT_ERROR, "Bridge failure: " + e, null));
        }
    }

    private void emitFromServer(String payload, JsonNode request) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(payload);
        } catch (JacksonException e) {
            logger.log(System.Logger.Level.WARNING, "Dropping a server message that is not JSON: {0}",
                    truncate(payload));
            return;
        }
        emit(parsed);
        if (request != null && isRequest(request, "initialize") && isResponseTo(parsed, request)) {
            onInitialized(parsed);
        }
    }

    private void onInitialized(JsonNode response) {
        var result = response.get("result");
        if (result == null) {
            return;
        }
        var version = result.get("protocolVersion");
        if (version != null && version.isString()) {
            protocolVersion = version.stringValue();
        }
        if (options.openStream() && sessionId != null && streamThread == null) {
            streamThread = Thread.ofVirtual().name("mcp-stdio-bridge-stream").start(this::holdStream);
        }
    }

    // ── GET stream ──────────────────────────────────────────────────────

    private void holdStream() {
        var backoff = Duration.ofMillis(500);
        while (!closed.get()) {
            var builder = request().header("Accept", EVENT_STREAM).GET();
            try {
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                var status = response.statusCode();
                try (var body = response.body()) {
                    if (status == 405) {
                        logger.log(System.Logger.Level.INFO, "MCP endpoint offers no GET stream (405); "
                                + "server-initiated messages arrive only on POST responses");
                        return;
                    }
                    if (status == 404 || status == 401 || status == 403) {
                        logger.log(System.Logger.Level.WARNING, "MCP GET stream refused with {0}: {1}",
                                status, readLimited(body));
                        return;
                    }
                    if (status == 200 && isEventStream(response)) {
                        openStreamBody.set(body);
                        backoff = Duration.ofMillis(500);
                        var events = new SseEventReader(body);
                        Optional<String> event;
                        while (!closed.get() && (event = events.next()).isPresent()) {
                            emitFromServer(event.get(), null);
                        }
                    } else {
                        logger.log(System.Logger.Level.WARNING, "MCP GET stream answered {0}; retrying", status);
                    }
                } finally {
                    openStreamBody.set(null);
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    logger.log(System.Logger.Level.DEBUG, "MCP GET stream dropped; reconnecting", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (closing.await(backoff.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
    }

    // ── DELETE ──────────────────────────────────────────────────────────

    /**
     * End the MCP session with {@code DELETE} now, leaving the bridge running.
     * {@link #close()} does this too.
     */
    public void terminate() {
        terminateSession();
    }

    private void terminateSession() {
        if (sessionId == null) {
            return;
        }
        var builder = request().timeout(Duration.ofSeconds(5)).DELETE();
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            logger.log(System.Logger.Level.DEBUG, "MCP session {0} ended ({1})", sessionId, response.statusCode());
        } catch (IOException e) {
            logger.log(System.Logger.Level.DEBUG, "Ending MCP session " + sessionId + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private HttpRequest.Builder request() {
        var builder = HttpRequest.newBuilder(options.endpoint());
        var session = sessionId;
        if (session != null) {
            builder.header(SESSION_HEADER, session);
        }
        var version = protocolVersion;
        if (version != null) {
            builder.header(VERSION_HEADER, version);
        }
        if (options.bearerToken() != null) {
            builder.header("Authorization", "Bearer " + options.bearerToken());
        }
        return builder;
    }

    /** Write one message as one line of JSON; a no-op for {@code null}. */
    private void emit(JsonNode message) {
        if (message == null) {
            return;
        }
        var line = MAPPER.writeValueAsString(message);
        outLock.lock();
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("stdout closed", e);
        } finally {
            outLock.unlock();
        }
    }

    /**
     * The JSON-RPC error answering {@code request}, or {@code null} when the
     * request was a notification or a response, which get no answer. A batch
     * gets an array of errors, one per element that has an {@code id}.
     */
    static JsonNode error(JsonNode request, int code, String message, String detail) {
        if (request != null && request.isArray()) {
            var errors = JsonNodeFactory.instance.arrayNode();
            for (var element : request) {
                var e = error(element, code, message, detail);
                if (e != null) {
                    errors.add(e);
                }
            }
            return errors.isEmpty() ? null : errors;
        }
        if (request != null && (!request.has("id") || !request.has("method"))) {
            return null;
        }
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("jsonrpc", "2.0");
        if (request == null) {
            envelope.putNull("id");
        } else {
            envelope.set("id", request.get("id"));
        }
        var error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (detail != null && !detail.isBlank()) {
            error.putObject("data").put("body", detail);
        }
        return envelope;
    }

    private static boolean isRequest(JsonNode message, String method) {
        var m = message.get("method");
        return message.isObject() && message.has("id") && m != null && m.isString() && m.stringValue().equals(method);
    }

    private static boolean isResponseTo(JsonNode response, JsonNode request) {
        return response.isObject() && response.has("id") && response.get("id").equals(request.get("id"));
    }

    private static boolean isEventStream(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(type -> type.toLowerCase(Locale.ROOT).startsWith(EVENT_STREAM))
                .orElse(false);
    }

    private static String httpErrorMessage(int status, HttpResponse<?> response) {
        var message = "MCP endpoint answered HTTP " + status;
        if (status == 401 || status == 403) {
            message += response.headers().firstValue("WWW-Authenticate").map(c -> " (" + c + ")").orElse("")
                    + "; set " + ENV_TOKEN;
        } else if (status == 404) {
            message += "; the MCP session may have expired, restart the client to open a new one";
        }
        return message;
    }

    private static String readLimited(InputStream body) {
        try {
            var bytes = body.readNBytes(MAX_ERROR_BODY_CHARS * 4);
            return truncate(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(System.Logger.Level.DEBUG, "Reading an error body failed", e);
            return null;
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_BODY_CHARS ? text : text.substring(0, MAX_ERROR_BODY_CHARS) + "…";
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            logger.log(System.Logger.Level.DEBUG, "Closing the GET stream failed", e);
        }
    }

    /**
     * The data of every {@code message} event in a complete SSE body, one
     * event per line.
     */
    public static String parseSseData(String sseBody) {
        var joined = new StringBuilder();
        try (var reader = new SseEventReader(new StringReader(sseBody + "\n\n"))) {
            Optional<String> event;
            while ((event = reader.next()).isPresent()) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(event.get());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return joined.toString();
    }
}
