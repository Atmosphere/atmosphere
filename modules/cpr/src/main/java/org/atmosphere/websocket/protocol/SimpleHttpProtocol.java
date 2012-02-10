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
package org.atmosphere.websocket.protocol;

import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket messages to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation by wrapping the Websocket message's bytes within
 * an {@link javax.servlet.http.HttpServletRequest}.
 * <p/>
 * The content-type is defined using {@link org.atmosphere.cpr.ApplicationConfig#WEBSOCKET_CONTENT_TYPE} property
 * The method is defined using {@link org.atmosphere.cpr.ApplicationConfig#WEBSOCKET_METHOD} property
 * <p/>
 *
 * @author Jeanfrancois Arcand
 */
public class SimpleHttpProtocol implements WebSocketProtocol, Serializable {

    private static final Logger logger = LoggerFactory.getLogger(SimpleHttpProtocol.class);
    private String contentType;
    private String methodType;
    private String delimiter;

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(AtmosphereConfig config) {
        String contentType = config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE);
        if (contentType == null) {
            contentType = "text/html";
        }
        this.contentType = contentType;

        String methodType = config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD);
        if (methodType == null) {
            methodType = "POST";
        }
        this.methodType = methodType;

        String delimiter = config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER);
        if (delimiter == null) {
            delimiter = "@@";
        }
        this.delimiter = delimiter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String d) {
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        if (resource == null) {
            logger.error("Invalid state. No AtmosphereResource has been suspended");
            return null;
        }
        String pathInfo = resource.getRequest().getPathInfo();
        if (d.startsWith(delimiter)) {
            String[] token = d.split(delimiter);
            pathInfo = token[1];
            d = token[2];
        }
        Map<String,Object> m = new HashMap<String, Object>();
        m.put(FrameworkConfig.WEBSOCKET_SUBPROTOCOL, FrameworkConfig.SIMPLE_HTTP_OVER_WEBSOCKET);

        List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();

        list.add(new AtmosphereRequest.Builder()
                .request(resource.getRequest())
                .method(methodType)
                .contentType(contentType)
                .body(d)
                .attributes(m)
                .pathInfo(pathInfo)
                .headers(WebSocketProcessor.configureHeader(resource.getRequest()))
                .build());

        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] d, final int offset, final int length) {
        return onMessage(webSocket, new String(d, offset, length));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(WebSocket webSocket) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(WebSocket webSocket) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.warn(t.getMessage() + " Status {} Message {}", t.response().getStatus(), t.response().getStatusMessage());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean inspectResponse() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String handleResponse(AtmosphereResponse res, String message) {
        // Should never be called
        return message;
    }

    @Override
    public byte[] handleResponse(AtmosphereResponse res, byte[] message, int offset, int length) {
        // Should never be called
        return message;
    }

}

