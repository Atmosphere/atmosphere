/*
* Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereResource;

import javax.servlet.http.HttpServletRequest;

/**
 * Represent a portable WebSocket implementation which can be used to write message.
 *
 * @author Jeanfrancois Arcand
 */
public interface WebSocket extends AsyncIOWriter {

    public final static String WEBSOCKET_INITIATED = WebSocket.class.getName() + ".initiated";
    public final static String WEBSOCKET_SUSPEND = WebSocket.class.getName() + ".suspend";
    public final static String WEBSOCKET_RESUME = WebSocket.class.getName() + ".resume";
    public final static String WEBSOCKET_ACCEPT_DONE = WebSocket.class.getName() + ".acceptDone";

    /**
     * Return the current {@link AtmosphereResource} representing the underlying connection and the original
     * {@link HttpServletRequest}
     *
     * @return the current {@link AtmosphereResource}
     */
    AtmosphereResource resource();
}
