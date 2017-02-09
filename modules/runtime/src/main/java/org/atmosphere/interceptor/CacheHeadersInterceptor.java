/*
 * Copyright 2017 Async-IO.org
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

import org.atmosphere.runtime.Action;
import org.atmosphere.runtime.ApplicationConfig;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereInterceptorAdapter;
import org.atmosphere.runtime.AtmosphereRequest;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResponse;
import org.atmosphere.runtime.FrameworkConfig;
import org.atmosphere.util.Utils;

import static org.atmosphere.runtime.HeaderConfig.CACHE_CONTROL;
import static org.atmosphere.runtime.HeaderConfig.EXPIRES;
import static org.atmosphere.runtime.HeaderConfig.PRAGMA;

/**
 * At interceptor for customizing the following headers: Expire, Cache-Control, Pragma,
 * Access-Control-Origin and Access-Control-Allow-Credential.
 *
 * @author Jeanfrancois Arcand
 */
public class CacheHeadersInterceptor extends AtmosphereInterceptorAdapter {

    private boolean injectCacheHeaders;
    private boolean writeHeaders;

    @Override
    public void configure(AtmosphereConfig config) {
        String nocache = config.getInitParameter(ApplicationConfig.NO_CACHE_HEADERS);
        injectCacheHeaders = nocache != null ? false : true;

        String wh = config.getInitParameter(FrameworkConfig.WRITE_HEADERS);
        writeHeaders = wh != null ? Boolean.parseBoolean(wh) : true;
    }

    @Override
    public Action inspect(AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        final AtmosphereResponse response = r.getResponse();
        final AtmosphereRequest request = r.getRequest();

        // For extension that aren't supporting this interceptor (like Jersey)
        request.setAttribute(ApplicationConfig.NO_CACHE_HEADERS, injectCacheHeaders);

        if (writeHeaders && injectCacheHeaders) {
            // Set to expire far in the past.
            response.setHeader(EXPIRES, "-1");
            // Set standard HTTP/1.1 no-cache headers.
            response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate");
            // Set standard HTTP/1.0 no-cache header.
            response.setHeader(PRAGMA, "no-cache");
        }
        return Action.CONTINUE;
    }

    public boolean injectCacheHeaders() {
        return injectCacheHeaders;
    }

    public boolean writeHeaders() {
        return writeHeaders;
    }

    public CacheHeadersInterceptor injectCacheHeaders(boolean injectCacheHeaders) {
        this.injectCacheHeaders = injectCacheHeaders;
        return this;
    }

    public CacheHeadersInterceptor writeHeaders(boolean writeHeaders) {
        this.writeHeaders = writeHeaders;
        return this;
    }

    @Override
    public String toString() {
        return "Default Response's Headers Interceptor";
    }
}
