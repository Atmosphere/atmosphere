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
package org.atmosphere.wasync.transport;

import org.atmosphere.wasync.Decoder;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.FunctionBinding;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base class for transport implementations providing common event dispatching,
 * decoder pipeline execution, and status management.
 */
public abstract class AbstractTransport implements Transport {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final List<FunctionBinding> functions = new CopyOnWriteArrayList<>();
    protected volatile Socket.STATUS status = Socket.STATUS.INIT;
    protected volatile CompletableFuture<Void> fireFuture;
    protected volatile CompletableFuture<Void> connectFuture;
    protected volatile boolean errorHandled;

    @Override
    public Transport registerFunction(FunctionBinding binding) {
        functions.add(binding);
        return this;
    }

    @Override
    public Socket.STATUS status() {
        return status;
    }

    @Override
    public boolean errorHandled() {
        return errorHandled;
    }

    @Override
    public void error(Throwable e) {
        errorHandled = true;
        dispatchEvent(Event.ERROR, e);
    }

    @Override
    public void future(CompletableFuture<Void> f) {
        this.fireFuture = f;
    }

    @Override
    public void connectedFuture(CompletableFuture<Void> f) {
        this.connectFuture = f;
    }

    @Override
    public void onThrowable(Throwable t) {
        logger.error("Transport error", t);
        status = Socket.STATUS.ERROR;
        dispatchEvent(Event.ERROR, t);
        if (connectFuture != null) {
            connectFuture.completeExceptionally(t);
        }
    }

    /**
     * Dispatch an event to all matching registered functions.
     */
    @SuppressWarnings("unchecked")
    protected void dispatchEvent(Event event, Object message) {
        for (var binding : functions) {
            if (binding.functionName().equalsIgnoreCase(event.name())) {
                try {
                    ((org.atmosphere.wasync.Function<Object>) binding.function()).on(message);
                } catch (Exception e) {
                    logger.warn("Error invoking function for event {}", event, e);
                }
            }
        }
    }

    /**
     * Run the message through the decoder pipeline and dispatch to matching functions.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void dispatchMessage(Event event, Object message, List<Decoder<?, ?>> decoders,
                                   org.atmosphere.wasync.FunctionResolver resolver) {
        Object decoded = message;

        for (Decoder decoder : decoders) {
            try {
                Object result = decoder.decode(event, decoded);
                if (result instanceof Decoder.Decoded<?> d) {
                    if (d.action() == Decoder.Decoded.Action.ABORT) {
                        return;
                    }
                    decoded = d.decoded();
                } else if (result != null) {
                    decoded = result;
                }
            } catch (Exception e) {
                logger.warn("Decoder error", e);
            }
        }

        for (var binding : functions) {
            if (resolver.resolve(binding.functionName(), event, decoded)) {
                try {
                    ((org.atmosphere.wasync.Function<Object>) binding.function()).on(decoded);
                } catch (Exception e) {
                    logger.warn("Error invoking function for message", e);
                }
            }
        }
    }
}
