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

/**
 * SPI interface for transport implementations. Each transport handles one
 * {@link Request.TRANSPORT} type (WebSocket, SSE, Streaming, or Long-Polling).
 *
 * <p>Transport implementations are registered via {@link OptionsBuilder#registerTransport(Transport)}.</p>
 */
public interface Transport {

    /**
     * The transport type this implementation handles.
     */
    Request.TRANSPORT name();

    /**
     * Register a function binding for event/message dispatching.
     *
     * @param binding the function binding
     * @return this transport for chaining
     */
    Transport registerFunction(FunctionBinding binding);

    /**
     * Handle an error.
     *
     * @param t the throwable
     */
    void onThrowable(Throwable t);

    /**
     * Close this transport.
     */
    void close();

    /**
     * The current connection status.
     */
    Socket.STATUS status();

    /**
     * Whether the transport has handled the error internally.
     *
     * @return {@code true} if the error was handled
     */
    boolean errorHandled();

    /**
     * Report an error to registered error handlers.
     *
     * @param e the error
     */
    void error(Throwable e);

    /**
     * Set the future for the fire operation.
     *
     * @param f the future
     */
    void future(CompletableFuture<Void> f);

    /**
     * Set the future for the connection operation.
     *
     * @param f the future
     */
    void connectedFuture(CompletableFuture<Void> f);
}
