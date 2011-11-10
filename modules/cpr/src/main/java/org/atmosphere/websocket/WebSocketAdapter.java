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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;

import javax.servlet.http.HttpServletRequest;

/**
 * Simple class used to expose internal objects to {@link WebSocket}
 */
public class WebSocketAdapter {

    private AtmosphereResource<?, ?> r;
    private HttpServletRequest request;
    private WebSocketHttpServletResponse<?> response;

    /**
     * Configure the {@link AtmosphereResource}
     *
     * @param r the {@link AtmosphereResource}
     */
    public WebSocketAdapter setAtmosphereResource(AtmosphereResource<?, ?> r) {
        this.r = r;
        return this;
    }

    public AtmosphereResource<?, ?> resource() {
        return r;
    }

    public WebSocketAdapter request(HttpServletRequest request) {
        this.request = request;
        return this;
    }

    public HttpServletRequest request() {
        return request;
    }

    public WebSocketAdapter response(WebSocketHttpServletResponse<?> response) {
        this.response = response;
        return this;
    }

    public WebSocketHttpServletResponse<?> response() {
        return response;
    }
}
