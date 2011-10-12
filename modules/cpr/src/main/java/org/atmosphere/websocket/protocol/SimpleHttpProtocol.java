/*
* Copyright 2011 Jeanfrancois Arcand
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
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHttpServletRequest;
import org.atmosphere.websocket.WebSocketHttpServletResponse;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;

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
public class SimpleHttpProtocol extends WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);
    private final String contentType;
    private final String methodType;
    private final String delimiter;

    public SimpleHttpProtocol(AtmosphereServlet atmosphereServlet, WebSocket webSocket) {
        super(atmosphereServlet, webSocket);
        String contentType = atmosphereServlet.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE);
        if (contentType == null) {
            contentType = "text/html";
        }
        this.contentType = contentType;

        String methodType = atmosphereServlet.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_METHOD);
        if (methodType == null) {
            methodType = "POST";
        }
        this.methodType = methodType;

        String delimiter = atmosphereServlet.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER);
        if (delimiter == null) {
            delimiter = "@@";
        }
        this.delimiter = delimiter;
    }

    public void parseMessage(String d) {
        try {
            String pathInfo = request().getPathInfo();
            if (d.startsWith(delimiter)) {
                String[] token = d.split(delimiter);
                pathInfo = token[1];
                d = token[2];
            }

            WebSocketHttpServletRequest r = new WebSocketHttpServletRequest.Builder()
                    .request(request())
                    .method(methodType)
                    .contentType(contentType)
                    .body(d)
                    .pathInfo(pathInfo)
                    .headers(configureHeader(request()))
                    .build();
            atmosphereServlet().doCometSupport(r, new WebSocketHttpServletResponse<WebSocket>(webSocketSupport()));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
        } catch (ServletException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public void parseMessage(byte[] d, final int offset, final int length) {
        try {
            String pathInfo = request().getPathInfo();
            if (d[0] == (byte)delimiter.charAt(0) && d[1] == (byte)delimiter.charAt(0)) {
                final String s = new String(d, offset, length, "UTF-8");
                String[] token = s.split(delimiter);
                pathInfo = token[1];
                d = token[2].getBytes("UTF-8");
            }

            WebSocketHttpServletRequest r = new WebSocketHttpServletRequest.Builder()
                    .request(request())
                    .method(methodType)
                    .contentType(contentType)
                    .body(d, offset, length)
                    .pathInfo(pathInfo)
                    .headers(configureHeader(request()))
                    .build();
            atmosphereServlet().doCometSupport(r, new WebSocketHttpServletResponse<WebSocket>(webSocketSupport()));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
        } catch (ServletException e) {
            logger.warn(e.getMessage(), e);
        }
    }

}

