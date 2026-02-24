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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A bidirectional connection to a server. Register {@link Function} callbacks to
 * receive events, then {@link #open(Request)} to connect and {@link #fire(Object)}
 * to send data.
 *
 * <pre>{@code
 * Socket socket = client.create();
 * socket.on(Event.OPEN, r -> System.out.println("Connected"))
 *       .on(Event.MESSAGE, m -> System.out.println("Received: " + m))
 *       .on(Event.ERROR, e -> e.printStackTrace())
 *       .open(request);
 *
 * socket.fire("hello");
 * }</pre>
 */
public interface Socket {

    /**
     * Connection status.
     */
    enum STATUS {
        INIT, OPEN, REOPENED, CLOSE, ERROR
    }

    /**
     * Register a {@link Function} to be invoked when an {@link Event} occurs.
     *
     * @param event    the event type
     * @param function the callback
     * @return this socket for chaining
     */
    Socket on(Event event, Function<?> function);

    /**
     * Register a {@link Function} to be invoked when a message matching the given name is received.
     *
     * @param functionMessage the message or function name to match
     * @param function        the callback
     * @return this socket for chaining
     */
    Socket on(String functionMessage, Function<?> function);

    /**
     * Register a {@link Function} to be invoked based on its type.
     *
     * @param function the callback
     * @return this socket for chaining
     */
    Socket on(Function<?> function);

    /**
     * Open the connection using the given request.
     *
     * @param request the request describing the target and transport
     * @return this socket for chaining
     */
    Socket open(Request request);

    /**
     * Open the connection with a timeout.
     *
     * @param request the request
     * @param timeout the maximum time to wait for the connection to open
     * @param unit    the time unit
     * @return this socket for chaining
     */
    Socket open(Request request, long timeout, TimeUnit unit);

    /**
     * Send data to the server. The data will be processed through any registered
     * {@link Encoder}s before being sent.
     *
     * @param data the data to send
     * @return a future that completes when the data has been sent
     */
    CompletableFuture<Void> fire(Object data);

    /**
     * Close the connection asynchronously.
     */
    void close();

    /**
     * The current connection status.
     */
    STATUS status();
}
