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

import org.atmosphere.wasync.Options;
import org.atmosphere.wasync.Transport;

import java.net.http.HttpClient;

/**
 * Default {@link Options} implementation backed by a {@link DefaultOptionsBuilder}.
 */
public class DefaultOptions implements Options {

    private final Transport transport;
    private final boolean reconnect;
    private final int reconnectTimeoutInMilliseconds;
    private final int reconnectAttempts;
    private final long waitBeforeUnlocking;
    private final HttpClient httpClient;
    private final boolean httpClientShared;
    private final int requestTimeoutInSeconds;
    private final boolean binary;

    protected DefaultOptions(DefaultOptionsBuilder builder) {
        this.transport = builder.getTransport();
        this.reconnect = builder.isReconnect();
        this.reconnectTimeoutInMilliseconds = builder.getReconnectTimeoutInMilliseconds();
        this.reconnectAttempts = builder.getReconnectAttempts();
        this.waitBeforeUnlocking = builder.getWaitBeforeUnlocking();
        this.httpClient = builder.getHttpClient();
        this.httpClientShared = builder.isHttpClientShared();
        this.requestTimeoutInSeconds = builder.getRequestTimeout();
        this.binary = builder.isBinary();
    }

    @Override
    public Transport transport() {
        return transport;
    }

    @Override
    public boolean reconnect() {
        return reconnect;
    }

    @Override
    public int reconnectTimeoutInMilliseconds() {
        return reconnectTimeoutInMilliseconds;
    }

    @Override
    public int reconnectAttempts() {
        return reconnectAttempts;
    }

    @Override
    public long waitBeforeUnlocking() {
        return waitBeforeUnlocking;
    }

    @Override
    public HttpClient httpClient() {
        return httpClient;
    }

    @Override
    public boolean httpClientShared() {
        return httpClientShared;
    }

    @Override
    public int requestTimeoutInSeconds() {
        return requestTimeoutInSeconds;
    }

    @Override
    public boolean binary() {
        return binary;
    }
}
