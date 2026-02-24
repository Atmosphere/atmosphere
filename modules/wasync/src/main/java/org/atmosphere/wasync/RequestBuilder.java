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
package org.atmosphere.wasync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A builder for creating {@link Request} instances.
 *
 * <pre>{@code
 * Request request = client.newRequestBuilder()
 *     .uri("ws://localhost:8080/chat")
 *     .transport(Request.TRANSPORT.WEBSOCKET)
 *     .encoder(new JsonEncoder())
 *     .build();
 * }</pre>
 *
 * @param <T> self-referencing type for fluent chaining
 */
public abstract class RequestBuilder<T extends RequestBuilder<T>> {

    protected Request.METHOD method = Request.METHOD.GET;
    protected String uri;
    protected final List<Request.TRANSPORT> transports = new ArrayList<>();
    protected final Map<String, List<String>> headers = new LinkedHashMap<>();
    protected final Map<String, List<String>> queryString = new LinkedHashMap<>();
    protected final List<Encoder<?, ?>> encoders = new ArrayList<>();
    protected final List<Decoder<?, ?>> decoders = new ArrayList<>();
    protected FunctionResolver resolver = FunctionResolver.DEFAULT;

    @SuppressWarnings("unchecked")
    protected T self() {
        return (T) this;
    }

    /**
     * Set the target URI.
     */
    public T uri(String uri) {
        this.uri = uri;
        return self();
    }

    /**
     * Add a transport to attempt, in order of priority.
     */
    public T transport(Request.TRANSPORT transport) {
        transports.add(transport);
        return self();
    }

    /**
     * Set the HTTP method.
     */
    public T method(Request.METHOD method) {
        this.method = method;
        return self();
    }

    /**
     * Add an HTTP header.
     */
    public T header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return self();
    }

    /**
     * Add a query string parameter.
     */
    public T queryString(String name, String value) {
        queryString.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return self();
    }

    /**
     * Add an encoder.
     */
    public T encoder(Encoder<?, ?> encoder) {
        encoders.add(encoder);
        return self();
    }

    /**
     * Add a decoder.
     */
    public T decoder(Decoder<?, ?> decoder) {
        decoders.add(decoder);
        return self();
    }

    /**
     * Set the function resolver.
     */
    public T resolver(FunctionResolver resolver) {
        this.resolver = resolver;
        return self();
    }

    /**
     * Build the request.
     */
    public abstract Request build();

    // Getters for subclasses and implementation

    public Request.METHOD method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public List<Request.TRANSPORT> transports() {
        return transports;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Map<String, List<String>> queryString() {
        return queryString;
    }

    public List<Encoder<?, ?>> encoders() {
        return encoders;
    }

    public List<Decoder<?, ?>> decoders() {
        return decoders;
    }

    public FunctionResolver resolver() {
        return resolver;
    }
}
