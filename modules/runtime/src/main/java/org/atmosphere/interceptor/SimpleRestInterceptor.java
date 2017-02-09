/*
 * Copyright 2017 Async-IO.org
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;

import org.atmosphere.runtime.Action;
import org.atmosphere.runtime.ApplicationConfig;
import org.atmosphere.runtime.AsyncIOInterceptor;
import org.atmosphere.runtime.AsyncIOInterceptorAdapter;
import org.atmosphere.runtime.AsyncIOWriter;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereInterceptorAdapter;
import org.atmosphere.runtime.AtmosphereInterceptorWriter;
import org.atmosphere.runtime.AtmosphereRequest;
import org.atmosphere.runtime.AtmosphereRequestImpl;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResourceEvent;
import org.atmosphere.runtime.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.runtime.AtmosphereResponse;
import org.atmosphere.runtime.Broadcaster;
import org.atmosphere.runtime.CompletionAware;
import org.atmosphere.runtime.DefaultBroadcaster;
import org.atmosphere.runtime.FrameworkConfig;
import org.atmosphere.util.ChunkConcatReaderPool;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Servlet init property to enable the detached mode in the response
     */
    public final static String PROTOCOL_DETACHED_KEY = "atmosphere.simple-rest.protocol.detached";

    /**
     * Connection request property to enable the detached mode in the response
     */
    public final static String X_ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED = "X-Atmosphere-SimpleRestProtocolDetached";

    protected final static String REQUEST_DISPATCHED = "request.dispatched";
    protected final static String REQUEST_ID = "request.id";

    private final static byte[] RESPONSE_TEMPLATE_HEAD = "{\"id\": \"".getBytes();
    private final static byte[] RESPONSE_TEMPLATE_BELLY = "\", \"data\": ".getBytes();
    private final static byte[] RESPONSE_TEMPLATE_BELLY_CONTINUE = "\", \"continue\":true, \"data\": ".getBytes();
    private final static byte[] RESPONSE_TEMPLATE_BELLY_DETACHED = "\", \"detached\": true".getBytes();
    private final static byte[] RESPONSE_TEMPLATE_BELLY_CONTINUE_DETACHED = "\", \"continue\":true, \"detached\": true".getBytes();
    private final static byte[] RESPONSE_TEMPLATE_TAIL = "}".getBytes();
    private final static byte[] RESPONSE_TEMPLATE_NEWLINE = "\n".getBytes();

    private final static String HEARTBEAT_BROADCASTER_NAME = "/simple-rest.heartbeat";
    private final static String HEARTBEAT_SCHEDULED = "heatbeat.scheduled";
    private final static String HEARTBEAT_TEMPLATE = "{\"heartbeat\": \"%s\", \"time\": %d}";
    private final static long DEFAULT_HEARTBEAT_INTERVAL = 60;

    private Map<String, AtmosphereResponse> suspendedResponses = new HashMap<String, AtmosphereResponse>();
    private ChunkConcatReaderPool readerPool = new ChunkConcatReaderPool();
    private boolean detached;

    private Broadcaster heartbeat;

    // REVISIST more appropriate to store this status?
    private boolean heartbeatScheduled;
    private final AsyncIOInterceptor interceptor = new Interceptor();

    public SimpleRestInterceptor() {
    }

    @Override
    public void configure(AtmosphereConfig config) {
        super.configure(config);
        detached = Boolean.parseBoolean(config.getInitParameter(PROTOCOL_DETACHED_KEY));
        //TODO make the heartbeat configurable
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
                public void close(AtmosphereResponse response) throws IOException {
                }
            });
            // REVISIT we need to keep this response's asyncwriter alive so that data can be written to the
            //   suspended response, but investigate if there is a better alternative.
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
                if (writer instanceof AtmosphereInterceptorWriter) {
                    ((AtmosphereInterceptorWriter)writer).interceptor(interceptor);
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
                //REVISIT use a more efficient approach for the detached mode (i.e.,avoid reading the message into a string)
                // read the message entity and dispatch a service call
                String body = IOUtils.readEntirelyAsString(r).toString();
                LOG.debug("Request message: '{}'", body);
                if (body.length() == 0) {
                    //TODO we might want to move this heartbeat scheduling after the handshake phase (if that is added)
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
        //REVISIT find a more efficient way to read and extract the message data
        JSONEnvelopeReader jer = new JSONEnvelopeReader(new StringReader(body));

        final String id = jer.getHeader("id");
        if (id != null) {
            request.localAttributes().put(REQUEST_ID, id);
        }

        boolean skip = false;
        final boolean continued = Boolean.valueOf(jer.getHeader("continue"));
        Reader reader = readerPool.getReader(id, false);
        if (reader != null) {
            skip = true;
        } else if (continued) {
            reader = readerPool.getReader(id, true);
        }

        if (skip) {
            // add the request data to the prevously dispatched request and skip dispatching a new one
            final Reader data = jer.getReader();
            if (data != null) {
                readerPool.addChunk(id, data, continued);
            }
            return null;
        } else {
            // prepare a new request for dispatching
            final String method = jer.getHeader("method");
            String path = jer.getHeader("path");
            final String type = jer.getHeader("type");
            final String accept = jer.getHeader("accept");

            AtmosphereRequest.Builder b = new AtmosphereRequestImpl.Builder();
            b.method(method != null ? method : "GET").pathInfo(path != null ? path: "/");
            if (accept != null) {
                Map<String, String> headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
                headers.put("Accept", accept);
                b.headers(headers);
            }
            b.contentType(type);
            final int qpos = path.indexOf('?');
            if (qpos > 0) {
                b.queryString(path.substring(qpos + 1));
                path = path.substring(0, qpos);
            }
            final Reader data = jer.getReader();
            if (data != null) {
                if (reader != null) {
                    b.reader(reader);
                    readerPool.addChunk(id, data, true);
                } else {
                    b.reader(data);
                }
            }
            String requestURL = request.getRequestURL() + path.substring(request.getRequestURI().length());
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
        //TODO find a nicer way to build the response entity
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (id != null) {
            try {
                baos.write(RESPONSE_TEMPLATE_HEAD);
                baos.write(id.getBytes());
                if (isDetached(request)) {
                    // {"id":...,  "detached": true}\n
                    // <payload>
                    if (isLastResponse(request, response)) {
                        baos.write(RESPONSE_TEMPLATE_BELLY_DETACHED);
                    } else {
                        baos.write(RESPONSE_TEMPLATE_BELLY_CONTINUE_DETACHED);
                    }
                    baos.write(RESPONSE_TEMPLATE_TAIL);
                    baos.write(RESPONSE_TEMPLATE_NEWLINE);
                    baos.write(payload);
                } else {
                    // {"id":..., "data": <payload>}
                    boolean isobj = isJSONObject(payload);
                    if (isLastResponse(request, response)) {
                        baos.write(RESPONSE_TEMPLATE_BELLY);
                    } else {
                        baos.write(RESPONSE_TEMPLATE_BELLY_CONTINUE);
                    }
                    if (!isobj) {
                        baos.write(quote(payload));
                    } else {
                        baos.write(payload);
                    }
                    baos.write(RESPONSE_TEMPLATE_TAIL);
                }

            } catch (IOException e) {
                //ignore as it can't happen
            }
        }
        return baos.toByteArray();
    }

    private void scheduleHeartbeat(AtmosphereResource r) {
        //REVISIT make the schedule configurable
        heartbeat.addAtmosphereResource(r);
        if (!heartbeatScheduled) {
            heartbeat.scheduleFixedBroadcast(String.format(getHeartbeatTemplate(), getHeartbeatTemplateArguments()),
                    DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
            heartbeatScheduled = true;
        }
    }

    protected static boolean isLastResponse(AtmosphereRequest request, AtmosphereResponse response) {
        return (response instanceof CompletionAware && ((CompletionAware)response).completed())
                || Boolean.TRUE != request.getAttribute(ApplicationConfig.RESPONSE_COMPLETION_AWARE);
    }

    protected boolean isDetached(AtmosphereRequest request) {
        // the default detached setting configured by the init property can be overrriden by the connection property
        final String prop = request.getHeader(X_ATMOSPHERE_SIMPLE_REST_PROTOCOL_DETACHED);
        return (detached && prop == null) || Boolean.valueOf(prop);
    }

    private void attachWriter(final AtmosphereResource r) {
        AtmosphereResponse res = r.getResponse();
        AsyncIOWriter writer = res.getAsyncIOWriter();

        if (writer instanceof AtmosphereInterceptorWriter) {
            ((AtmosphereInterceptorWriter)writer).interceptor(interceptor, 0);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
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

    /*
     * A custom json envelope reader to parse a character sequence by extracting the key value pairs
     * but leaves the data value unparsed so that it can be subsequently consumed directly as a chracter seqeunce.
     */
    static class JSONEnvelopeReader {
        private Reader reader;
        private Map<String, String> headers;
        private boolean datap;
        private boolean detachedp;
        private int peek = -1;

        public JSONEnvelopeReader(Reader reader) throws IOException {
            this.reader = reader;
            this.headers = new HashMap<String, String>();

            prepare();
        }

        public String getHeader(String name) {
            return headers.get(name);
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public Reader getReader() {
            if (!datap && !detachedp) {
                return null;
            }

            return new Reader() {
                private int b;
                @Override
                public int read(char[] cbuf, int off, int len) throws IOException {
                    int n = reader.read(cbuf, off, len);
                    if (n > 0) {
                        boolean escaping = false;
                        char quot = 0;
                        for (int i = off; i < n; i++) {
                            char c = cbuf[i];
                            if (c == '{' && !escaping) {
                                b++;
                            } else if (c == '}' && !escaping) {
                                b--;
                                if (b < 0) {
                                    // past the logical eof
                                    n--;
                                }
                            } else if ((c == '"' || c == '\'') && !escaping) {
                                if (c == quot) {
                                    quot = 0;
                                } else {
                                    quot = c;
                                }
                            } else if (c == '\\' && quot != 0 && !escaping) {
                                escaping = true;
                            } else if (escaping) {
                                escaping = false;
                            }
                        }
                    }
                    return n;
                }

                @Override
                public void close() throws IOException {
                    reader.close();
                }

                @Override
                public boolean ready() throws IOException {
                    return reader.ready();
                }
            };
        }


        private void prepare() throws IOException {
            int c = next(true);
            if (c == '{') {
                for (;;) {
                    String name = nextName();
                    c = next(true);
                    if (c == ':') {
                        if ("data".equals(name)) {
                            datap = true;
                            break;
                        } else if ("detached".equals(name)) {
                            if (Boolean.valueOf(nextValue())) {
                                detachedp = true;
                            }
                        } else {
                            headers.put(name, nextValue());
                        }
                    } else {
                        throw new IOException("invalid value: missing name-separator ':'");
                    }
                    c = next(true);
                    if (c != ',') {
                        if (c == '}' && detachedp) {
                            while (c != -1) {
                                c = next(false);
                                if (c == '\n') {
                                    break;
                                }
                            }
                        } else {
                            unread(c);
                        }
                        break;
                    }
                }
            } else {
                throw new IOException("invalid object: missing being-object '{'");
            }
        }

        private String nextName() throws IOException {
            int c = next(true);
            if (c == '"' || c == '\'') {
                return nextQuoted(c);
            }
            throw new IOException("invalid name: missing quote '\"'");
        }

        private String nextValue() throws IOException {
            int c = next(true);
            if (c == '"' || c == '\'') {
                // quoted string
                return nextQuoted(c);
            } else if (c == 't' || c == 'f' || ('0' <= c && c <= '9')) {
                // true, false, or number
                unread(c);
                return nextNonQuoted();
            }
            throw new IOException("invalid value: unquoted non literals");
        }

        private String nextQuoted(int quot) throws IOException {
            StringBuilder sb = new StringBuilder();
            boolean escaping = false;
            int c;
            while ((c = next(false)) != -1) {
                if (c == '\\' && !escaping) {
                    escaping = true;
                } else if (c == quot && !escaping) {
                    break;
                } else {
                    sb.append((char) c);
                    if (escaping) {
                        escaping = false;
                    }
                }
            }
            if (c != -1) {
                return sb.toString();
            }
            throw new IOException("invalid quoted string: missing quotation");
        }

        private String nextNonQuoted() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = next(false)) != -1) {
                if (c == '}' || c == ',' || isWS(c)) {
                    unread(c);
                    break;
                } else {
                    sb.append((char) c);
                }
            }
            if (c != -1) {
                return sb.toString();
            }
            throw new IOException("invalid value: non-terminated");
        }

        private int next(boolean skipws) throws IOException {
            int c;
            if (peek != -1) {
                c = peek;
                peek = -1;
            } else {
                while ((c = reader.read()) != -1 && skipws && isWS(c));
            }
            return c;
        }

        private void unread(int c) {
            peek = c;
        }

        private boolean isWS(int c) {
            return c == 0x20 || c == 0x09 || c == 0x0a || c == 0x0d;
        }
    }
}
