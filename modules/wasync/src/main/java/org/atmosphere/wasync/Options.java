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
 * Configuration options for a {@link Socket}.
 */
public interface Options {

    /**
     * The registered transport implementation.
     */
    Transport transport();

    /**
     * Whether to reconnect on disconnect.
     */
    boolean reconnect();

    /**
     * Time in milliseconds to wait before attempting a reconnection.
     */
    int reconnectTimeoutInMilliseconds();

    /**
     * Maximum number of reconnection attempts. {@code -1} means unlimited.
     */
    int reconnectAttempts();

    /**
     * Time in milliseconds to wait before unlocking the open() call.
     */
    long waitBeforeUnlocking();

    /**
     * The {@link HttpClient} instance to use for connections.
     * If {@code null}, a default client will be created.
     */
    HttpClient httpClient();

    /**
     * Whether the {@link HttpClient} is shared and should not be closed
     * when the socket closes.
     */
    boolean httpClientShared();

    /**
     * Request timeout in seconds.
     */
    int requestTimeoutInSeconds();

    /**
     * Whether to send data as binary frames.
     */
    boolean binary();
}
