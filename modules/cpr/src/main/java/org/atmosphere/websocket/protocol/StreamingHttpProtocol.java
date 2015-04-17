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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocolStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static org.atmosphere.websocket.protocol.ProtocolUtil.constructRequest;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket stream to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation by wrapping the Websocket message's bytes within
 * an {@link javax.servlet.http.HttpServletRequest}.
 * <p/>
 * The content-type is defined using {@link org.atmosphere.cpr.ApplicationConfig#WEBSOCKET_CONTENT_TYPE} property
 * The method is defined using {@link org.atmosphere.cpr.ApplicationConfig#WEBSOCKET_METHOD} property
 * <p/>
 *
 * @author Jeanfrancois Arcand
 */
public class StreamingHttpProtocol implements WebSocketProtocolStream {

    private static final Logger logger = LoggerFactory.getLogger(StreamingHttpProtocol.class);
    protected final static String TEXT = "text/plain";
    protected String contentType = "text/plain";
    protected String methodType = "POST";
    protected String delimiter = "@@";
    protected boolean destroyable;
    private final SimpleHttpProtocol delegate;

    public StreamingHttpProtocol(){
        delegate = new SimpleHttpProtocol();
    }

    @Override
    public void configure(AtmosphereConfig config) {
        String contentType = config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE);
        if (contentType == null) {
            contentType = "text/plain";
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

        String s = config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
        if (s != null && Boolean.valueOf(s)) {
            destroyable = true;
        } else {
            destroyable = false;
        }
    }

    @Override
    public List<AtmosphereRequest> onTextStream(WebSocket webSocket, Reader r) {
        //Converting to a string and delegating to onMessage(WebSocket webSocket, String d) causes issues because the binary data may not be a valid string.
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        if (resource == null) {
            logger.trace("The WebSocket has been closed before the message was processed.");
            return null;
        }

        AtmosphereRequest request = resource.getRequest();
        request.setAttribute(FrameworkConfig.WEBSOCKET_SUBPROTOCOL, FrameworkConfig.STREAMING_HTTP_OVER_WEBSOCKET);

        List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();
        list.add(constructRequest(webSocket, request.getPathInfo(), request.getRequestURI(), methodType, contentType.equalsIgnoreCase(TEXT) ? null : contentType, destroyable).reader(r).build());
        return list;
    }

    @Override
    public List<AtmosphereRequest> onBinaryStream(WebSocket webSocket, InputStream stream) {
        //Converting to a string and delegating to onMessage(WebSocket webSocket, String d) causes issues because the binary data may not be a valid string.
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        if (resource == null) {
            logger.trace("The WebSocket has been closed before the message was processed.");
            return null;
        }

        AtmosphereRequest request = resource.getRequest();
        request.setAttribute(FrameworkConfig.WEBSOCKET_SUBPROTOCOL, FrameworkConfig.STREAMING_HTTP_OVER_WEBSOCKET);

        List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();
        list.add(constructRequest(webSocket, request.getPathInfo(), request.getRequestURI(), methodType, contentType.equalsIgnoreCase(TEXT) ? null : contentType, destroyable).inputStream(stream).build());

        return list;
    }

    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String data) {
        return delegate.onMessage(webSocket, data);
    }

    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] data, int offset, int length) {
        return delegate.onMessage(webSocket, data, offset, length);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
    }

    @Override
    public void onClose(WebSocket webSocket) {
    }

    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.warn(t.getMessage() + " Status {} Message {}", t.response().getStatus(), t.response().getStatusMessage());
    }
}

