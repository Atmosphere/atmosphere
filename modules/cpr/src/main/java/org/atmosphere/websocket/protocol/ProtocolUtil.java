/*
 * Copyright 2015 Async-IO.org
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
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolUtil {

    protected static AtmosphereRequest.Builder constructRequest(WebSocket websocket,
                                                                String pathInfo,
                                                                String requestURI,
                                                                String methodType,
                                                                String contentType,
                                                                boolean destroyable) {
        AtmosphereRequest request = AtmosphereResourceImpl.class.cast(websocket.resource()).getRequest(false);
        Map<String, Object> m = attributes(websocket);

        // We need to create a new AtmosphereRequest as WebSocket message may arrive concurrently on the same connection.
        AtmosphereRequest.Builder b = (new AtmosphereRequest.Builder()
                .request(request)
                .method(methodType)
                .contentType(contentType == null ? request.getContentType() : contentType)
                .attributes(m)
                .pathInfo(pathInfo)
                .contextPath(request.getContextPath())
                .servletPath(request.getServletPath())
                .requestURI(requestURI)
                .requestURL(request.requestURL())
                .destroyable(destroyable)
                .headers(request.headersMap())
                .session(websocket.resource().session()));
        return b;
    }

    private static Map<String, Object> attributes(WebSocket websocket) {
        Map<String, Object> m = new ConcurrentHashMap<String, Object>();
        m.putAll(websocket.attributes());
        return m;
    }
}
