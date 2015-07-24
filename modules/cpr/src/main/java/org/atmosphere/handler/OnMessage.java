/*
 * Copyright 2015 Jean-Francois Arcand
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
package org.atmosphere.handler;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Simple {@link AtmosphereHandler} that must be used with the {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor}
 * and {@link org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor} to reduce the handling of the suspend/resume/disconnect and
 * broadcast operation.
 * <p/>
 * You can also safely used this class with {@link org.atmosphere.cpr.BroadcasterCache}.
 * method
 *
 * @author Jeanfrancois Arcand
 */
public abstract class OnMessage<T> extends AbstractReflectorAtmosphereHandler {

    public final static String MESSAGE_DELIMITER = "|";
    private final Logger logger = LoggerFactory.getLogger(OnMessage.class);

    @Override
    public final void onRequest(AtmosphereResource resource) throws IOException {
        if (resource.getRequest().getMethod().equalsIgnoreCase("GET")) {
            onOpen(resource);
        }
    }

    @Override
    public final void onStateChange(AtmosphereResourceEvent event) throws IOException {
        AtmosphereResponse response = ((AtmosphereResourceImpl) event.getResource()).getResponse(false);

        logger.trace("{} with event {}", event.getResource().uuid(), event);
        if (event.isCancelled() || event.isClosedByApplication() || event.isClosedByClient()) {
            onDisconnect(response);
        } else if (event.getMessage() != null && List.class.isAssignableFrom(event.getMessage().getClass())) {
            List<T> messages = List.class.cast(event.getMessage());
            for (T t : messages) {
                onMessage(response, t);
            }
        } else if (event.isResuming()) {
            onResume(response);
        } else if (event.isResumedOnTimeout()) {
            onTimeout(response);
        } else if (event.isSuspended()) {
            onMessage(response, (T) event.getMessage());
        }
        postStateChange(event);
    }

    @Override
    public final void destroy() {
    }

    /**
     * This method will be invoked when an connection has been received and not haven't yet be suspended. Note that
     * the connection will be suspended AFTER the method has been invoked when used with {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor}
     *
     * @param resource an {@link AtmosphereResource}
     * @throws IOException
     */
    public void onOpen(AtmosphereResource resource) throws IOException {
    }

    /**
     * Implement this method to get invoked every time a new {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
     * occurs.
     *
     * @param response an {@link AtmosphereResponse}
     * @param message  a message of type T
     */
    abstract public void onMessage(AtmosphereResponse response, T message) throws IOException;

    /**
     * This method will be invoked during the process of resuming a connection. By default this method does nothing.
     *
     * @param response an {@link AtmosphereResponse}.
     * @throws IOException
     */
    public void onResume(AtmosphereResponse response) throws IOException {
    }

    /**
     * This method will be invoked when a suspended connection times out, e.g no activity has occurred for the
     * specified time used when suspending. By default this method does nothing.
     *
     * @param response an {@link AtmosphereResponse}.
     * @throws IOException
     */
    public void onTimeout(AtmosphereResponse response) throws IOException {
    }

    /**
     * This method will be invoked when the underlying WebServer detects a connection has been closed. Please
     * note that not all WebServer supports that features (see Atmosphere's WIKI for help). By default this method does nothing.
     *
     * @param response an {@link AtmosphereResponse}.
     * @throws IOException
     */
    public void onDisconnect(AtmosphereResponse response) throws IOException {
    }
}