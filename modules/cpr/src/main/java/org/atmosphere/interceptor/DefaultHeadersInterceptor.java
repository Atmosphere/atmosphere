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
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;

import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.atmosphere.cpr.HeaderConfig.CACHE_CONTROL;
import static org.atmosphere.cpr.HeaderConfig.EXPIRES;
import static org.atmosphere.cpr.HeaderConfig.PRAGMA;

/**
 * At interceptor for customizing the following headers: Expire, Cache-Control, Pragma,
 * Access-Control-Origin and Access-Control-Allow-Credential.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultHeadersInterceptor extends AtmosphereInterceptorAdapter {
    private boolean injectCacheHeaders;
    private boolean enableAccessControl;
    private boolean writeHeaders;

    @Override
    public void configure(AtmosphereConfig config) {
        String nocache = config.getInitParameter(ApplicationConfig.NO_CACHE_HEADERS);
        injectCacheHeaders = nocache != null ? false : true;

        String ac = config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);
        enableAccessControl = ac != null ? !Boolean.parseBoolean(ac) : true;

        String wh = config.getInitParameter(FrameworkConfig.WRITE_HEADERS);
        writeHeaders = wh != null ? Boolean.parseBoolean(wh) : true;
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();
        final AtmosphereRequest request = r.getRequest();

        // For extension that aren't supporting this interceptor (like Jersey)
        request.setAttribute(ApplicationConfig.NO_CACHE_HEADERS, injectCacheHeaders);
        request.setAttribute(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, enableAccessControl);

        if (writeHeaders && injectCacheHeaders) {
            // Set to expire far in the past.
            response.setHeader(EXPIRES, "-1");
            // Set standard HTTP/1.1 no-cache headers.
            response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate");
            // Set standard HTTP/1.0 no-cache header.
            response.setHeader(PRAGMA, "no-cache");
        }

        if (writeHeaders && enableAccessControl) {
            response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN,
                    request.getHeader("Origin") == null ? "*" : request.getHeader("Origin"));
            response.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "Default Response's Headers Interceptor";
    }
}
