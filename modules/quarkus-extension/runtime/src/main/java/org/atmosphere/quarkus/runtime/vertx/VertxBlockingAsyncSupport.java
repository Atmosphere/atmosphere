/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.quarkus.runtime.vertx;

import jakarta.servlet.ServletException;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.Utils;

import java.io.IOException;

/**
 * {@link BlockingIOCometSupport} for the Vert.x container mode: every request runs
 * on its own virtual thread, so a suspended connection parks that thread on the
 * latch instead of holding a platform thread. WebSocket connections are upgraded
 * by {@link VertxAtmosphereHandler} and fed through the {@code WebSocketProcessor};
 * a WebSocket suspend does not park a thread, because the open socket is what
 * keeps the connection (the same rule {@code Servlet30CometSupport} applies by
 * not starting an async context for a WebSocket request).
 */
public class VertxBlockingAsyncSupport extends BlockingIOCometSupport {

    public VertxBlockingAsyncSupport(AtmosphereConfig config) {
        super(config);
    }

    @Override
    protected void suspend(Action action, AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {
        if (Utils.webSocketEnabled(req)) {
            // Blocking here would park the connection's message worker forever.
            return;
        }
        super.suspend(action, req, res);
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public String getContainerName() {
        return "Quarkus Vert.x (virtual threads)";
    }
}
