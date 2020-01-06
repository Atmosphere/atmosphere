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
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.Utils;

/**
 * CORS support.
 *
 * @author Janusz Sobolewski
 */
public class CorsInterceptor extends AtmosphereInterceptorAdapter {

    private final String EXPOSE_HEADERS = "X-Atmosphere-tracking-id, " + HeaderConfig.X_HEARTBEAT_SERVER;

    private boolean enableAccessControl = true;

    @Override
    public void configure(AtmosphereConfig config) {
        String ac = config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);
        if (ac != null) {
            enableAccessControl = !Boolean.parseBoolean(ac);
        }
    }

    @Override
    public Action inspect(AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        if (!enableAccessControl) return Action.CONTINUE;

        AtmosphereRequest req = r.getRequest();
        AtmosphereResponse res = r.getResponse();

        if (req.getHeader("Origin") != null && res.getHeader("Access-Control-Allow-Origin") == null) {
            res.addHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
            res.addHeader("Access-Control-Expose-Headers", EXPOSE_HEADERS);
            res.setHeader("Access-Control-Allow-Credentials", "true");
        }

        if ("OPTIONS".equals(req.getMethod())) {
            res.setHeader("Access-Control-Allow-Methods", "OPTIONS, GET, POST");
            res.setHeader("Access-Control-Allow-Headers",
                    "Origin, Content-Type, AuthToken, X-Atmosphere-Framework, X-Requested-With, "
                            + EXPOSE_HEADERS
                            + ", X-Atmosphere-Transport, X-Atmosphere-TrackMessageSize, X-atmo-protocol");
            res.setHeader("Access-Control-Max-Age", "-1");

            return Action.SKIP_ATMOSPHEREHANDLER;
        }

        return Action.CONTINUE;
    }

    public boolean enableAccessControl() {
        return enableAccessControl;
    }

    public CorsInterceptor enableAccessControl(boolean enableAccessControl) {
        this.enableAccessControl = enableAccessControl;
        return this;
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.FIRST_BEFORE_DEFAULT;
    }

    @Override
    public String toString() {
        return "CORS Interceptor Support";
    }
}
