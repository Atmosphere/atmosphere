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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Add proper header for Nginx's buffering http://wiki.nginx.org/HttpProxyModule#proxy_buffering
 * <br/>
 * This interceptor set the 'X-Accel-Buffering : No' on the {@link org.atmosphere.cpr.AtmosphereResponse} before it gets suspended.
 *
 * @author Jeanfrancois Arcand
 */
public class NginxInterceptor extends AtmosphereInterceptorAdapter {

    private final static Logger logger = LoggerFactory.getLogger(NginxInterceptor.class);

    @Override
    public Action inspect(AtmosphereResource r) {
        try {
            r.getResponse().addHeader("X-Accel-Buffering", "No");
        } catch (Throwable t) {
            logger.trace("", t);
        }
        return Action.CONTINUE;
    }

}
