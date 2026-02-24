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

import java.net.http.HttpClient;

/**
 * A builder for creating {@link Options} instances.
 *
 * <pre>{@code
 * Options options = client.newOptionsBuilder()
 *     .reconnect(true)
 *     .reconnectAttempts(5)
 *     .build();
 * }</pre>
 *
 * @param <U> the options type
 * @param <T> self-referencing type for fluent chaining
 */
public abstract class OptionsBuilder<U extends Options, T extends OptionsBuilder<U, T>> {

    protected int requestTimeout = 300;
    protected boolean reconnect = true;
    protected int reconnectTimeoutInMilliseconds = 1000;
    protected int reconnectAttempts = -1;
    protected long waitBeforeUnlocking = 2000;
    protected Transport transport;
    protected HttpClient httpClient;
    protected boolean httpClientShared;
    protected boolean binary;

    @SuppressWarnings("unchecked")
    protected T self() {
        return (T) this;
    }

    /**
     * Set the request timeout in seconds.
     */
    public T requestTimeoutInSeconds(int requestTimeout) {
        this.requestTimeout = requestTimeout;
        return self();
    }

    /**
     * Register a custom transport implementation.
     */
    public T registerTransport(Transport transport) {
        this.transport = transport;
        return self();
    }

    /**
     * Whether to reconnect on disconnect.
     */
    public T reconnect(boolean reconnect) {
        this.reconnect = reconnect;
        return self();
    }

    /**
     * Pause before reconnection in seconds.
     */
    public T pauseBeforeReconnectInSeconds(int pause) {
        this.reconnectTimeoutInMilliseconds = pause * 1000;
        return self();
    }

    /**
     * Pause before reconnection in milliseconds.
     */
    public T pauseBeforeReconnectInMilliseconds(int pause) {
        this.reconnectTimeoutInMilliseconds = pause;
        return self();
    }

    /**
     * Maximum number of reconnection attempts. {@code -1} means unlimited.
     */
    public T reconnectAttempts(int reconnectAttempts) {
        this.reconnectAttempts = reconnectAttempts;
        return self();
    }

    /**
     * Time in milliseconds to wait before unlocking the open() call.
     */
    public T waitBeforeUnlocking(long waitBeforeUnlocking) {
        this.waitBeforeUnlocking = waitBeforeUnlocking;
        return self();
    }

    /**
     * Set the {@link HttpClient} to use.
     */
    public T httpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        return self();
    }

    /**
     * Set the {@link HttpClient} and whether it is shared.
     */
    public T httpClient(HttpClient httpClient, boolean shared) {
        this.httpClient = httpClient;
        this.httpClientShared = shared;
        return self();
    }

    /**
     * Whether to send data as binary frames.
     */
    public T binary(boolean binary) {
        this.binary = binary;
        return self();
    }

    /**
     * Build the options.
     */
    public abstract U build();

    // Getters for subclasses in other packages

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public boolean isReconnect() {
        return reconnect;
    }

    public int getReconnectTimeoutInMilliseconds() {
        return reconnectTimeoutInMilliseconds;
    }

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    public long getWaitBeforeUnlocking() {
        return waitBeforeUnlocking;
    }

    public Transport getTransport() {
        return transport;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public boolean isHttpClientShared() {
        return httpClientShared;
    }

    public boolean isBinary() {
        return binary;
    }
}
