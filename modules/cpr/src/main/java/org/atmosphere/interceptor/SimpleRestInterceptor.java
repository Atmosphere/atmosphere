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
package org.atmosphere.interceptor;

import jakarta.servlet.ServletException;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.CompletionAware;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.ChunkConcatReaderPool;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An Atmosphere interceptor to enable a simple rest binding protocol.
 * This protocol is a simplified version of SwaggerSocket with some new features
 * https://github.com/swagger-api/swaggersocket
 *
 * This interceptor currently handles both Websocket and SSE protocols.
 * It was originally developed for enabling atmosphere for kafka-rest at
 * https://github.com/elakito/kafka-rest-atmosphere
 *
 * @author elakito
 */
public class SimpleRestInterceptor extends AtmosphereInterceptorAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleRestInterceptor.class);
    /**
     * The internal header consisting of the {tracking-id}#{request-id}
     */
    public final static String X_REQUEST_KEY = "X-Request-Key";

    protected final static String REQUEST_DISPATCHED = "request.dispatched";
    protected final static String REQUEST_ID = "request.id";

    private final static String HEARTBEAT_BROADCASTER_NAME = "/simple-rest.heartbeat";
    private final static String HEARTBEAT_SCHEDULED = "heatbeat.scheduled";
    private final static String HEARTBEAT_TEMPLATE = "{\"heartbeat\": \"%s\", \"time\": %d}";
    private final static long DEFAULT_HEARTBEAT_INTERVAL = 60;

    // Lenient parsing — the legacy org.json backend accepted single-quoted
    // keys/values, so we keep that contract on the wire to avoid breaking
    // existing SimpleRestInterceptor clients.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    private final Map<String, AtmosphereResponse> suspendedResponses = new ConcurrentHashMap<>();
    private final ChunkConcatReaderPool readerPool = new ChunkConcatReaderPool();

    private Broadcaster heartbeat;

    // REVISIST more appropriate to store this status?
    private final AtomicBoolean heartbeatScheduled = new AtomicBoolean();
    private final AsyncIOInterceptor interceptor = new Interceptor();

    public SimpleRestInterceptor() {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        super.configure(config);
        heartbeat = config.getBroadcasterFactory().lookup(DefaultBroadcaster.class, getHeartbeatBroadcasterName());
        if (heartbeat == null) {
            heartbeat = config.getBroadcasterFactory().get(DefaultBroadcaster.class, getHeartbeatBroadcasterName());
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        if (AtmosphereResource.TRANSPORT.WEBSOCKET != r.transport()
                && AtmosphereResource.TRANSPORT.SSE != r.transport()
                && AtmosphereResource.TRANSPORT.POLLING != r.transport()) {
            LOG.debug("Skipping for non websocket request");
            return Action.CONTINUE;
        }
        if (AtmosphereResource.TRANSPORT.POLLING == r.transport()) {
            final String saruuid = (String)r.getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
            final AtmosphereResponse suspendedResponse = suspendedResponses.get(saruuid);
            if (suspendedResponse == null) {
                LOG.warn("No suspended response found for resource: {}", saruuid);
                return Action.CONTINUE;
            }
            LOG.debug("Attaching a proxy writer to suspended response");
            r.getResponse().asyncIOWriter(new AtmosphereInterceptorWriter() {
                @Override
                public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
                    suspendedResponse.write(data);
                    suspendedResponse.flushBuffer();
                    return this;
                }

                @Override
                public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
                    suspendedResponse.write(data);
                    suspendedResponse.flushBuffer();
                    return this;
                }

                @Override
                public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
                    suspendedResponse.write(data, offset, length);
                    suspendedResponse.flushBuffer();
                    return this;
                }

                @Override
                public void close(AtmosphereResponse response) {
                }
            });
            // Keep the response's AsyncWriter alive so that data can be written to the
            //   suspended response.
            r.getResponse().destroyable(false);
            return Action.CONTINUE;
        }

        r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
            @Override
            public void onSuspend(AtmosphereResourceEvent event) {
                final String srid = (String)event.getResource().getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
                LOG.debug("Registrering suspended resource: {}", srid);
                suspendedResponses.put(srid, event.getResource().getResponse());

                AsyncIOWriter writer = event.getResource().getResponse().getAsyncIOWriter();
                if (writer == null) {
                    writer = new AtmosphereInterceptorWriter();
                    r.getResponse().asyncIOWriter(writer);
                }
                if (writer instanceof AtmosphereInterceptorWriter aiw) {
                    aiw.interceptor(interceptor);
                }
            }

            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                super.onDisconnect(event);
                final String srid = (String)event.getResource().getRequest().getAttribute(ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
                LOG.debug("Unregistrering suspended resource: {}", srid);
                suspendedResponses.remove(srid);
            }

        });

        AtmosphereRequest request = r.getRequest();
        if (request.getAttribute(REQUEST_DISPATCHED) == null) {
            try {
                // Read the message entity and dispatch a service call
                String body = IOUtils.readEntirelyAsString(r).toString();
                LOG.debug("Request message: '{}'", body);
                if (body.length() == 0) {
                    // Schedule heartbeat on first empty-body WebSocket/SSE message
                    if ((AtmosphereResource.TRANSPORT.WEBSOCKET == r.transport() ||
                            AtmosphereResource.TRANSPORT.SSE == r.transport())
                            && request.getAttribute(HEARTBEAT_SCHEDULED) == null) {
                        r.suspend();
                        scheduleHeartbeat(r);
                        request.setAttribute(HEARTBEAT_SCHEDULED, "true");
                        return Action.SUSPEND;
                    }
                    return Action.CANCELLED;
                }

                AtmosphereRequest ar = createAtmosphereRequest(request, body);
                if (ar == null) {
                    return Action.CANCELLED;
                }
                AtmosphereResponse response = r.getResponse();
                ar.localAttributes().put(REQUEST_DISPATCHED, "true");

                request.removeAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE);
                response.request(ar);

                attachWriter(r);

                Action action = r.getAtmosphereConfig().framework().doCometSupport(ar, response);
                if (action.type() == Action.TYPE.SUSPEND) {
                    ar.destroyable(false);
                    response.destroyable(false);
                }
                return Action.CANCELLED;
            } catch (IOException | ServletException e) {
                LOG.error("Failed to process", e);
            }
        }

        return Action.CONTINUE;
    }

    protected String getHeartbeatBroadcasterName() {
        return HEARTBEAT_BROADCASTER_NAME;
    }

    protected String getHeartbeatTemplate() {
        return HEARTBEAT_TEMPLATE;
    }

    protected Object[] getHeartbeatTemplateArguments() {
        return new Object[]{UUID.randomUUID().toString(), System.currentTimeMillis()};
    }

    protected AtmosphereRequest createAtmosphereRequest(AtmosphereRequest request, String body) throws IOException {
        String uuid = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        Reader msgreader = new StringReader(body);
        JsonNode jsonpart = parseJsonPart(msgreader);
        final String id = getString(jsonpart, "id");
        if (id != null) {
            request.localAttributes().put(REQUEST_ID, id);
        }

        boolean skip = false;
        final boolean continued = getBoolean(jsonpart, "continue");
        Reader reader = readerPool.getReader(id, false);
        if (reader != null) {
            skip = true;
        } else if (continued) {
            reader = readerPool.getReader(id, true);
        }

        if (skip) {
            // add the request data to the prevously dispatched request and skip dispatching a new one
            readerPool.addChunk(id, msgreader, continued);
            return null;
        } else {
            // prepare a new request for dispatching
            final String method = getString(jsonpart, "method");
            String path = getString(jsonpart, "path");
            final String type = getString(jsonpart, "type");
            final String accept = getString(jsonpart, "accept");

            AtmosphereRequest.Builder b = new AtmosphereRequestImpl.Builder();
            b.method(method != null ? method : "GET").pathInfo(path != null ? path: "/");
            var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            // put the 'tracking-id#request-id' into the request's headers
            headers.put(X_REQUEST_KEY, String.format("%s#%s", uuid, id));
            if (accept != null) {
                headers.put("Accept", accept);
            }
            if (type != null) {
                b.contentType(type);
            }
            b.headers(headers);
            final int qpos = path != null ? path.indexOf('?') : 0;
            if (qpos > 0) {
                b.queryString(path.substring(qpos + 1));
                path = path.substring(0, qpos);
            }

            if (reader != null) {
                b.reader(reader);
                readerPool.addChunk(id, msgreader, true);
            } else {
                b.reader(msgreader);
            }
            final String requestURL = request.getRequestURL() +
                    (path != null ? path.substring(request.getRequestURI().length()) : null);
            b.requestURI(path).requestURL(requestURL).request(request);

            return b.build();
        }
    }

    protected byte[] createResponse(AtmosphereResponse response, byte[] payload) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("createResponse for payload {}", new String(payload));
        }
        AtmosphereRequest request = response.request();
        String id = (String)request.getAttribute(REQUEST_ID);
        if (id == null) {
            // control response such as heartbeat or plain responses
            return payload;
        }
        var baos = new ByteArrayOutputStream();
        try {
            // {"id":"<id>", "code": code, ...}<payload>
            ObjectNode jsonpart = MAPPER.createObjectNode();
            jsonpart.put("id", id);
            jsonpart.put("code", response.getStatus());
            String ct = response.getContentType();
            if (ct != null) {
                jsonpart.put("type", ct);
            }
            if (!isLastResponse(request, response)) {
                jsonpart.put("continue", true);
            }
            baos.write(jsonpart.toString().getBytes(StandardCharsets.UTF_8));
            baos.write(payload);
        } catch (IOException e) {
            //ignore as it can't happen
        }
        return baos.toByteArray();
    }

    private void scheduleHeartbeat(AtmosphereResource r) {
        heartbeat.addAtmosphereResource(r);
        if (heartbeatScheduled.compareAndSet(false, true)) {
            heartbeat.scheduleFixedBroadcast(String.format(getHeartbeatTemplate(), getHeartbeatTemplateArguments()),
                    DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
        }
    }

    protected static boolean isLastResponse(AtmosphereRequest request, AtmosphereResponse response) {
        return (response instanceof CompletionAware ca && ca.completed())
                || Boolean.TRUE != request.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_AWARE);
    }

    private void attachWriter(final AtmosphereResource r) {
        AtmosphereResponse res = r.getResponse();
        AsyncIOWriter writer = res.getAsyncIOWriter();

        if (writer instanceof AtmosphereInterceptorWriter aiw) {
            aiw.interceptor(interceptor, 0);
        }
    }

    private final class Interceptor extends AsyncIOInterceptorAdapter {
        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
            return createResponse(response, responseDraft);
        }
    }

    protected static boolean isJSONObject(byte[] b) {
        return b.length > 0 && (b[0] == (byte)'[' || b[0] == (byte)'{');
    }
    protected static byte[] quote(byte[] b) {
        var baos = new ByteArrayOutputStream();
        baos.write('"');
        for (byte c : b) {
            if (c == '"') {
                baos.write('\\');
            }
            baos.write(c);
        }
        baos.write('"');
        return baos.toByteArray();
    }

    /**
     * Parse the JSON header part of a SwaggerSocket-style request body
     * into a Jackson tree.
     *
     * <p><strong>API change in 4.0.42:</strong> the return type changed
     * from the legacy {@code org.json.JSONObject} to Jackson's
     * {@link JsonNode} when the {@code org.json:json} dependency was
     * dropped (CVE hygiene). External subclasses that read the parsed
     * JSON should migrate to {@link JsonNode} or
     * {@link tools.jackson.databind.ObjectMapper#convertValue} for a
     * typed shape.</p>
     *
     * @throws IOException when the input is not parseable JSON.
     */
    protected static JsonNode parseJsonPart(Reader reader) throws IOException {
        String headerJson = readBalancedObject(reader);
        if (headerJson == null) {
            throw new IOException("Empty SimpleRest JSON header part");
        }
        try {
            return MAPPER.readTree(headerJson);
        } catch (JacksonException ex) {
            throw new IOException("Malformed SimpleRest JSON header part: " + ex.getMessage(), ex);
        }
    }

    /**
     * Read exactly one balanced JSON object from {@code reader} and
     * return it as a string, leaving the reader positioned at the first
     * character after the closing {@code '}'}. The legacy {@code JSONTokener}
     * backend exposed this property implicitly; Jackson's tree reader
     * buffers ahead and would otherwise swallow the body that follows
     * the SwaggerSocket-style header.
     *
     * <p>Tracks string quoting (both {@code "} and {@code '}, since the
     * caller-side parser tolerates single quotes) and backslash escapes
     * so braces inside strings don't unbalance the depth count.</p>
     *
     * @return the JSON object text, or {@code null} if the reader was empty/whitespace-only
     * @throws IOException if the input does not start with {@code '{'} or is unterminated
     */
    private static String readBalancedObject(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean started = false;
        boolean inString = false;
        boolean escaped = false;
        char quoteChar = 0;
        int c;
        while ((c = reader.read()) != -1) {
            char ch = (char) c;
            if (!started) {
                if (Character.isWhitespace(ch)) {
                    continue;
                }
                if (ch != '{') {
                    throw new IOException(
                            "SimpleRest JSON header part must start with '{', got '" + ch + "'");
                }
                started = true;
            }
            sb.append(ch);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    escaped = true;
                } else if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                inString = true;
                quoteChar = ch;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return sb.toString();
                }
            }
        }
        if (started) {
            throw new IOException("SimpleRest JSON header part is unterminated");
        }
        return null;
    }

    /**
     * Read a string field from the parsed JSON header. Returns
     * {@code null} when the field is absent, null-valued, or
     * non-textual — matching the legacy {@code getString} swallow-on-miss
     * semantic.
     */
    protected static String getString(JsonNode obj, String key) {
        if (obj == null) {
            return null;
        }
        JsonNode node = obj.get(key);
        if (node == null || node.isNull() || !node.isString()) {
            return null;
        }
        return node.stringValue();
    }

    /**
     * Read a boolean field from the parsed JSON header. Returns
     * {@code false} when the field is absent, null-valued, or
     * non-boolean — matching the legacy {@code getBoolean} swallow-on-miss
     * semantic.
     */
    protected static boolean getBoolean(JsonNode obj, String key) {
        if (obj == null) {
            return false;
        }
        JsonNode node = obj.get(key);
        if (node == null || node.isNull() || !node.isBoolean()) {
            return false;
        }
        return node.booleanValue();
    }

}
