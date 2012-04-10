/*
 * Copyright 2012 Jean-Francois Arcand
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
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Simple {@link AtmosphereHandler} which redirect the first request to the web application welcome page.
 * Once the WebSocket upgrade happens, this class just invoke the {@link #upgrade(AtmosphereResource)} method.
 * <p/>
 * Application should override the {@link #upgrade(AtmosphereResource)}.
 * Application should override the {@link #onStateChange} if they do not want to reflect/send back all Websocket
 * messages to all connections.
 *
 * @author Jeanfrancois Arcand
 */
public class SimpleWebSocketAtmosphereHandler extends AbstractReflectorAtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(SimpleWebSocketAtmosphereHandler.class);

    /**
     * This method redirect the request to the server main page (index.html, index.jsp, etc.) and then execute the
     * {@link #upgrade(AtmosphereResource)}.
     *
     * @param r The {@link AtmosphereResource}
     * @throws IOException
     */
    @Override
    public final void onRequest(AtmosphereResource r) throws IOException {
        if (!r.getResponse().getClass().isAssignableFrom(AtmosphereResponse.class)) {
            try {
                r.getAtmosphereConfig().getServletContext()
                        .getNamedDispatcher(r.getAtmosphereConfig().getDispatcherName())
                        .forward(r.getRequest(), r.getResponse());
            } catch (ServletException e) {
                IOException ie = new IOException();
                ie.initCause(e);
                throw ie;
            }
        } else {
            upgrade(r);
        }
    }

    /**
     * WebSocket upgrade. This is usually inside that method that you decide if a connection
     * needs to be suspended or not. Override this method for specific operations like configuring their own
     * {@link Broadcaster}, {@link BroadcastFilter} , {@link BroadcasterCache} etc.
     *
     * @param resource an {@link AtmosphereResource}
     * @throws IOException
     */
    public void upgrade(AtmosphereResource resource) throws IOException {
        logger.debug("Suspending request: {}", resource.getRequest());
        resource.suspend(-1, false);
    }

    @Override
    public void destroy() {
    }

}
