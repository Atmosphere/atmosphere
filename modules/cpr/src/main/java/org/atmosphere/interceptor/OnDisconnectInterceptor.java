/*
 * Copyright 2008-2020 Async-IO.org
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
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.util.Utils.closeMessage;


/**
 * When the browser close the connection, the atmosphere.js will send an unsubscribe message to tell
 * framework the browser is disconnecting.
 *
 * @author Jeanfrancois Arcand
 */
public class OnDisconnectInterceptor extends AtmosphereInterceptorAdapter {

    private final Logger logger = LoggerFactory.getLogger(OnDisconnectInterceptor.class);
    private AtmosphereConfig config;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        AtmosphereRequest request = ((AtmosphereResourceImpl) r).getRequest(false);
        String uuid = r.uuid();
        if (closeMessage(request)) {
            if (config.resourcesFactory() == null || uuid == null ) {
                logger.debug("Illegal state for uuid {} and AtmosphereResourceFactory {}", r.uuid(), config.resourcesFactory());
                return Action.CANCELLED;
            }

            AtmosphereResource ss = config.resourcesFactory().find(uuid);

            if (ss == null) {
                logger.debug("No Suspended Connection found for {}. Using the AtmosphereResource associated with the close message", uuid);
                ss = r;
            }

            logger.debug("AtmosphereResource {} disconnected", uuid);

            // Block websocket closing detection
            ((AtmosphereResourceEventImpl) ss.getAtmosphereResourceEvent()).isClosedByClient(true);

            if (AsynchronousProcessor.class.isAssignableFrom(config.framework().getAsyncSupport().getClass())) {
                ((AsynchronousProcessor) config.framework().getAsyncSupport()).completeLifecycle(ss, false);
            }
            return Action.CANCELLED;
        }
        return Action.CONTINUE;
    }

    public String toString() {
        return "Browser disconnection detection";
    }
}

