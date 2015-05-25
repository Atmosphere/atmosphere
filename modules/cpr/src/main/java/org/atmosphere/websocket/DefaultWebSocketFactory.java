/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;

import javax.inject.Inject;

public class DefaultWebSocketFactory implements WebSocketFactory {

    @Inject
    private AtmosphereResourceFactory factory;

    @Override
    public WebSocket find(String uuid) {
        AtmosphereResource r = factory.find(uuid);
        if (r != null) {
            return AtmosphereResourceImpl.class.cast(r).webSocket();
        }
        return null;
    }
}
