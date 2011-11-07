/*
* Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.websocket;

import org.atmosphere.cpr.AtmosphereServlet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A WebSocket based protocol implementation. Implement this call to process WebSocket message and dispatch it to
 * Atmosphere or any consumer of WebSocket message
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocketProtocol {

    /**
     * Allow an implementation to query the AtmosphereConfig of init-param, etc.
     *
     * @param config {@link org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig}
     */
    void configure(AtmosphereServlet.AtmosphereConfig config);

    /**
     * Parse the WebSocket message, and delegate the processing to the {@link org.atmosphere.cpr.AtmosphereServlet#cometSupport} or
     * to any existing technology. Invoking  {@link org.atmosphere.cpr.AtmosphereServlet#cometSupport} will delegate the request processing
     * to the {@link org.atmosphere.cpr.AtmosphereHandler} implementation. Returning null means this implementation will
     * handle itself the processing/dispatching of the WebSocket's request;
     * <br>
     * As an example, this is how Websocket messages are delegated to the
     * Jersey runtime.
     * <br>
     *
     * @param webSocket The {@link WebSocket} connection
     * @param data      The Websocket message
     */
    HttpServletRequest onMessage(WebSocket webSocket, String data);

    /**
     * Parse the WebSocket message, and delegate the processing to the {@link org.atmosphere.cpr.AtmosphereServlet#cometSupport} or
     * to any existing technology. Invoking  {@link org.atmosphere.cpr.AtmosphereServlet#cometSupport} will delegate the request processing
     * to the {@link org.atmosphere.cpr.AtmosphereHandler} implementation. Returning null means this implementation will
     * handle itself the processing/dispatching of the WebSocket's request;
     * <br>
     * As an example, this is how Websocket messages are delegated to the
     * Jersey runtime.
     * <br>
     *
     * @param webSocket The {@link WebSocket} connection
     * @param offset    offset message index
     * @param length    length of the message.
     */
    HttpServletRequest onMessage(WebSocket webSocket, byte[] data, int offset, int length);

    /**
     * Invoked when a WebSocket is opened
     * @param webSocket {@link WebSocket}
     */
    void onOpen(WebSocket webSocket);

    /**
     * Invoked when a WebSocket is closed
     * @param webSocket {@link WebSocket}
     */
    void onClose(WebSocket webSocket);

    /**
     * Return true if this implementation will manipulate/change the WebSocket message;
     * @return true if this implementation will manipulate/change the WebSocket message;
     */
    boolean inspectWebSocketMessage();

    /**
     * Give a chance to a WebSocketProtocol to modify the final response using a fake {@link HttpServletResponse} that was
     * dispatched to a ServletContainer and it's framework or application running it.
     *
     * This method is only invoked when {@link WebSocketProtocol#onMessage(WebSocket, String)} return a valid
     * {@link HttpServletRequest}, meaning the request will be dispatched to a Servlet Container.
     *
     * @param res {@link HttpServletResponse}
     * @param message the WebSocket message;
     * @return a new response String
     */
    String handleResponse(WebSocketHttpServletResponse<?> res, String message);

    /**
     * Give a chance to a WebSocketProtocol to modify the final response using a fake {@link HttpServletResponse} that was
     * dispatched to a ServletContainer and it's framework or application running it.
     *
     * This method is only invoked when {@link WebSocketProtocol#onMessage(WebSocket, String)} return a valid
     * {@link HttpServletRequest}, meaning the request will be dispatched to a Servlet Container.
     *
     * @param res {@link HttpServletResponse}
     * @param message the WebSocket message;
     * @param offset offset of the message
     * @param length the length of the message
     * @return a new byte[] message.
     */
    byte[] handleResponse(WebSocketHttpServletResponse<?> res, byte[] message, int offset, int length);
}
