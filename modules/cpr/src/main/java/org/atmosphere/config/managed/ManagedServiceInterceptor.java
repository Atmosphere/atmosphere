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
package org.atmosphere.config.managed;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ManagedServiceInterceptor extends BroadcastOnPostAtmosphereInterceptor {

    private final static Logger logger = LoggerFactory.getLogger(ManagedServiceInterceptor.class);
    private ManagedAtmosphereHandler proxy;


    public ManagedServiceInterceptor(ManagedAtmosphereHandler proxy) {
        this.proxy = proxy;
    }

    @Override
    public void postInspect(AtmosphereResource r) {
        if (r.getRequest().getMethod().equalsIgnoreCase("POST")) {

            StringBuilder b = read(r);
            if (b.length() > 0) {
                Object o = null;
                try {
                    o = proxy.invoke(b.toString());
                } catch (IOException e) {
                    logger.error("", e);
                }
                if (o != null) {
                    r.getBroadcaster().broadcast(o);
                }
            } else {
                logger.warn("{} received an empty body", ManagedServiceInterceptor.class.getSimpleName());
            }
        }
    }
}
