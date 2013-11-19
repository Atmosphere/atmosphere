/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;

/**
 * Mark WebSocket Message as suspended.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketMessageSuspendInterceptor extends AtmosphereInterceptorAdapter {

    @Override
    public Action inspect(AtmosphereResource r) {

        if (r.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET) &&
                r.getRequest().getAttribute(FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE) == null) {
            AtmosphereResourceImpl.class.cast(r).action().type(Action.TYPE.SUSPEND_MESSAGE);
        }
        return Action.CONTINUE;
    }
}
