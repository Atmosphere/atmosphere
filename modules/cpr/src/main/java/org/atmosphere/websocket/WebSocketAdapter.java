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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;

import java.io.IOException;

/**
 * Simple class used to expose internal objects to {@link WebSocket}
 * 
 * @author Jeanfrancois Arcand
 */
public abstract class WebSocketAdapter implements WebSocket{

    private AtmosphereResource r;
    protected long lastWrite = 0;
    protected WebSocketResponseFilter webSocketResponseFilter = WebSocketResponseFilter.NOOPS_WebSocketResponseFilter;
    protected final boolean binaryWrite;

    public WebSocketAdapter(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_BINARY_WRITE);
        if (s != null && Boolean.parseBoolean(s)) {
            binaryWrite = true;
        } else {
            binaryWrite = false;
        }
    }

    /**
     * Configure the {@link AtmosphereResource}
     *
     * @param r the {@link AtmosphereResource}
     */
    public WebSocketAdapter setAtmosphereResource(AtmosphereResource r) {
        this.r = r;
        return this;
    }

    @Override
    public void close(AtmosphereResponse r) throws IOException {
    }

    @Override
    public AtmosphereResource resource() {
        return r;
    }

    public long lastTick() {
        return lastWrite == -1 ? System.currentTimeMillis() : lastWrite;
    }

    public WebSocket webSocketResponseWriter(WebSocketResponseFilter w) {
        this.webSocketResponseFilter = w;
        return this;
    }
}
