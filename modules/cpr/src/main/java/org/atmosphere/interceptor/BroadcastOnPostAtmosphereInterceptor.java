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
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.atmosphere.util.IOUtils.isBodyEmpty;
import static org.atmosphere.util.IOUtils.readEntirely;

/**
 * This read the request's body and invoke the associated {@link org.atmosphere.cpr.Broadcaster} of an {@link AtmosphereResource}.
 * The broadcast always happens AFTER the request has been delivered to an {@link org.atmosphere.cpr.AtmosphereHandler}.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcastOnPostAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private final static Logger logger = LoggerFactory.getLogger(BroadcastOnPostAtmosphereInterceptor.class);

    @Override
    public Action inspect(AtmosphereResource r) {
        return Action.CONTINUE;
    }


    @Override
    public void postInspect(AtmosphereResource r) {
        if (r.getRequest().getMethod().equalsIgnoreCase("POST")) {
            AtmosphereRequest request = r.getRequest();
            Object o;
            try {
                o = readEntirely(r);
            } catch (IOException e) {
                logger.warn("", e);
                return;
            }

            if (isBodyEmpty(o)) {
                logger.warn("{} received an empty body", request);
                return;
            }
            r.getBroadcaster().broadcast(o);
        }
    }
}
