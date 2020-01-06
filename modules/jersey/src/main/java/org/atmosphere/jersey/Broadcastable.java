/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.jersey;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple placeholder that can be used to broadcast message using a specific
 * {@link Broadcaster}.
 *
 * @author Jeanfrancois Arcand
 */
public class Broadcastable {

    private static final Logger logger = LoggerFactory.getLogger(Broadcastable.class);

    private final Object message;
    private final Broadcaster b;
    private final Object callerMessage;

    public Broadcastable(Broadcaster b) {
        this.b = b;
        message = "";
        callerMessage = "";
    }

    /**
     * Broadcast the <tt>message</tt> to the set of suspended {@link AtmosphereResource}, and write back
     * the <tt>message</tt> to the request which invoked the current resource method.
     *
     * @param message the message which will be broadcasted
     * @param b       the {@link Broadcaster}
     */
    public Broadcastable(Object message, Broadcaster b) {
        this.b = b;
        this.message = message;
        callerMessage = message;
    }

    /**
     * Broadcast the <tt>message</tt> to the set of suspended {@link AtmosphereResource}, and write back
     * the <tt>callerMessage</tt> to the request which invoked the current resource method.
     *
     * @param message       the message which will be broadcasted
     * @param callerMessage the message which will be sent back to the request.
     * @param b             the {@link Broadcaster}
     */
    public Broadcastable(Object message, Object callerMessage, Broadcaster b) {
        this.b = b;
        this.message = message;
        this.callerMessage = callerMessage;
        if (callerMessage == null) {
            throw new NullPointerException("callerMessage cannot be null");
        }
    }

    /**
     * Broadcast the message.
     *
     * @return the transformed message ({@link BroadcastFilter})
     */
    public Object broadcast() {
        try {
            return b.broadcast(message).get();
        } catch (Exception ex) {
            logger.error("failed to broadcast message: " + message, ex);
        }
        return null;
    }

    public Object getMessage() {
        return message;
    }

    public Broadcaster getBroadcaster() {
        return b;
    }

    public Object getResponseMessage() {
        return callerMessage;
    }
}
