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
package org.atmosphere.wasync.impl;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Encoder;
import org.atmosphere.wasync.FunctionResolver;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.RequestBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Default {@link Request} implementation backed by a {@link RequestBuilder}.
 */
public class DefaultRequest implements Request {

    private final METHOD method;
    private final String uri;
    private final List<TRANSPORT> transports;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> queryString;
    private final List<Encoder<?, ?>> encoders;
    private final List<Decoder<?, ?>> decoders;
    private final FunctionResolver resolver;

    protected DefaultRequest(RequestBuilder<?> builder) {
        this.method = builder.method();
        this.uri = builder.uri();
        this.transports = List.copyOf(builder.transports());
        this.headers = Collections.unmodifiableMap(builder.headers());
        this.queryString = Collections.unmodifiableMap(builder.queryString());
        this.encoders = List.copyOf(builder.encoders());
        this.decoders = List.copyOf(builder.decoders());
        this.resolver = builder.resolver();
    }

    @Override
    public List<TRANSPORT> transport() {
        return transports;
    }

    @Override
    public METHOD method() {
        return method;
    }

    @Override
    public Map<String, List<String>> headers() {
        return headers;
    }

    @Override
    public Map<String, List<String>> queryString() {
        return queryString;
    }

    @Override
    public List<Encoder<?, ?>> encoders() {
        return encoders;
    }

    @Override
    public List<Decoder<?, ?>> decoders() {
        return decoders;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public FunctionResolver functionResolver() {
        return resolver;
    }
}
