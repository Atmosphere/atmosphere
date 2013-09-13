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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * CORS support
 *
 * @author Janusz Sobolewski
 */
public class CorsInterceptor extends AtmosphereInterceptorAdapter {

    private final AtomicReference<String> emptyMessage = new AtomicReference<String>("");

    @Override
    public Action inspect(AtmosphereResource resource) {
        
        AtmosphereRequest req = resource.getRequest();                                   
        AtmosphereResponse res = resource.getResponse();
        
        if(req.getHeader("Origin") != null){
            res.addHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
            res.addHeader("Access-Control-Expose-Headers", "X-Cache-Date, X-Atmosphere-tracking-id");
            res.setHeader("Access-Control-Allow-Credentials", "true");
        }

        if("OPTIONS".equals(req.getMethod())){
            res.setHeader("Access-Control-Allow-Methods", "OPTIONS, GET, POST");
            res.setHeader("Access-Control-Allow-Headers",
                    "Origin, Content-Type, X-Atmosphere-Framework, X-Cache-Date, X-Atmosphere-tracking-id, X-Atmosphere-Transport, X-Atmosphere-TrackMessageSize, X-atmo-protocol");
            res.setHeader("Access-Control-Max-Age", "-1");

            res.write(emptyMessage.get());
            
            return Action.SKIP_ATMOSPHEREHANDLER;
        } 
        
        return Action.CONTINUE;
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
