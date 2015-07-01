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
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProtocolUtil {
    private final static Logger logger = LoggerFactory.getLogger(ProtocolUtil.class);

    protected static AtmosphereRequestImpl.Builder constructRequest(WebSocket webSocket,
                                                                String pathInfo,
                                                                String requestURI,
                                                                String methodType,
                                                                String contentType,
                                                                boolean destroyable) {

        AtmosphereResource resource = webSocket.resource();
        AtmosphereRequest request = AtmosphereResourceImpl.class.cast(resource).getRequest(false);
        Map<String, Object> m = attributes(webSocket, request);

        // We need to create a new AtmosphereRequest as WebSocket message may arrive concurrently on the same connection.
        AtmosphereRequestImpl.Builder b = (new AtmosphereRequestImpl.Builder()
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
                .session(resource.session()));
        return b;
    }

    private static Map<String, Object> attributes(WebSocket webSocket, AtmosphereRequest request) {
        Map<String, Object> m = new ConcurrentHashMap<String, Object>();
        m.putAll(webSocket.attributes());
        return m;
    }
}
