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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;

/**
 * Represent a portable WebSocket implementation which can be used to write message.
 *
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocket extends AsyncIOWriterAdapter {

    public final static String WEBSOCKET_INITIATED = WebSocket.class.getName() + ".initiated";
    public final static String WEBSOCKET_SUSPEND = WebSocket.class.getName() + ".suspend";
    public final static String WEBSOCKET_RESUME = WebSocket.class.getName() + ".resume";
    public final static String WEBSOCKET_ACCEPT_DONE = WebSocket.class.getName() + ".acceptDone";

    private AtmosphereResource r;
    protected long lastWrite = 0;
    protected WebSocketResponseFilter webSocketResponseFilter = WebSocketResponseFilter.NOOPS_WebSocketResponseFilter;
    protected final boolean binaryWrite;

    public WebSocket(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_BINARY_WRITE);
        if (s != null && Boolean.parseBoolean(s)) {
            binaryWrite = true;
        } else {
            binaryWrite = false;
        }
    }

    public WebSocket() {
        binaryWrite = false;
    }

    /**
     * Associate an {@link AtmosphereResource} to this WebSocket
     *
     * @param r an {@link AtmosphereResource} to this WebSocket
     * @return this
     */
    public WebSocket resource(AtmosphereResource r) {

        // Make sure we carry what was set at the onOpen stage.
        if (this.r != null && r != null) {
            // TODO: This is all over the place and quite ugly (the cast). Need to fix this in 1.1
            AtmosphereResourceImpl.class.cast(r).cloneState(this.r);
        }
        this.r = r;
        return this;
    }

    /**
     * Return the an {@link AtmosphereResource} used by this WebSocket
     *
     * @return {@link AtmosphereResource}
     */
    public AtmosphereResource resource() {
        return r;
    }

    /**
     * The last time, in milliseconds, a write operation occurred.
     *
     * @return this
     */
    public long lastWriteTimeStampInMilliseconds() {
        return lastWrite == -1 ? System.currentTimeMillis() : lastWrite;
    }

    /**
     * Associate a {@link WebSocketResponseFilter} that will be invoked before any write operation.
     *
     * @param w {@link WebSocketResponseFilter}
     * @return this
     */
    public WebSocket webSocketResponseFilter(WebSocketResponseFilter w) {
        this.webSocketResponseFilter = w;
        return this;
    }
}
