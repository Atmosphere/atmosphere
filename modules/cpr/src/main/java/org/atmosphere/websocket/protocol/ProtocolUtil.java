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
package org.atmosphere.websocket.protocol;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolUtil {

    protected static AtmosphereRequest.Builder constructRequest(AtmosphereResource resource,
                                                                String pathInfo,
                                                                String requestURI,
                                                                String methodType,
                                                                String contentType,
                                                                boolean destroyable) {
        AtmosphereRequest request = resource.getRequest();
        Map<String, Object> m = attributes(request);

        // We need to create a new AtmosphereRequest as WebSocket message may arrive concurrently on the same connection.
        AtmosphereRequest.Builder b = (new AtmosphereRequest.Builder()
                .request(request)
                .method(methodType)
                .contentType(contentType)
                .attributes(m)
                .pathInfo(pathInfo)
                .requestURI(requestURI)
                .destroyable(destroyable)
                .headers(request.headersMap())
                .session(resource.session()));
        return b;
    }

    private static Map<String, Object> attributes(AtmosphereRequest request) {
        Map<String, Object> m = new ConcurrentHashMap<String, Object>();
        m.put(FrameworkConfig.WEBSOCKET_SUBPROTOCOL, FrameworkConfig.SIMPLE_HTTP_OVER_WEBSOCKET);
        m.putAll(request.attributes());
        return m;
    }
}
