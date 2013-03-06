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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * When the browser close the connection, the atmosphere.js will send an unsubscribe message to tell
 * framework the browser is disconnecting.
 *
 * @author Jeanfrancois Arcand
 */
public class OnDisconnectInterceptor implements AtmosphereInterceptor {

    private final Logger logger = LoggerFactory.getLogger(OnDisconnectInterceptor.class);

    @Override
    public void configure(AtmosphereConfig config) {
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        AtmosphereRequest request = r.getRequest();
        String s = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        String uuid = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        if (s != null && uuid != null && s.equalsIgnoreCase(HeaderConfig.DISCONNECT)) {
            logger.debug("AtmosphereResource {} disconnected", uuid);
            AtmosphereResource ss = AtmosphereResourceFactory.getDefault().find(uuid);
            if (ss != null) {
                ss.notifyListeners(new AtmosphereResourceEventImpl(AtmosphereResourceImpl.class.cast(r), true, false));
                try {
                    ss.getRequest().setAttribute(AtmosphereResourceImpl.PRE_SUSPEND, "");
                    AtmosphereResourceImpl.class.cast(ss).cancel();
                } catch (IOException e) {
                    logger.trace("", e);
                }
            }
            return Action.CANCELLED;
        }
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
    }

    public String toString(){
        return "Browser disconnection detection";
    }
}
