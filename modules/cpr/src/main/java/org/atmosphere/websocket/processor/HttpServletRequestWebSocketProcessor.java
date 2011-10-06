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
package org.atmosphere.websocket.processor;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket messages to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation by wrapping the Websocket message's bytes within
 * an {@link javax.servlet.http.HttpServletRequest}.
 * <p/>
 * The content-type is defined using {@link org.atmosphere.cpr.AtmosphereServlet#WEBSOCKET_CONTENT_TYPE} property
 * The method is defined using {@link org.atmosphere.cpr.AtmosphereServlet#WEBSOCKET_METHOD} property
 * <p/>
 *
 * @author Jeanfrancois Arcand
 */
public class HttpServletRequestWebSocketProcessor extends WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);
    private final String contentType;
    private final String methodType;

    public HttpServletRequestWebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocket webSocket) {
        super(atmosphereServlet, webSocket);
        String contentType = atmosphereServlet.config.getInitParameter(AtmosphereServlet.WEBSOCKET_CONTENT_TYPE);
        if (contentType == null) {
            contentType = "text/html";
        }
        this.contentType = contentType;

        String methodType = atmosphereServlet.config.getInitParameter(AtmosphereServlet.WEBSOCKET_METHOD);
        if (methodType == null) {
            methodType = "POST";
        }
        this.methodType = methodType;
    }

    public void broadcast(final String data) {
        try {
            atmosphereServlet().doCometSupport(new HttpServletRequestWrapper(request()) {

                private ByteInputStream bis = new ByteInputStream(data.getBytes(), 0, data.getBytes().length);
                private BufferedReader br = new BufferedReader(new StringReader(data));

                @Override
                public String getMethod() {
                    return methodType;
                }

                @Override
                public String getContentType() {
                    return contentType;
                }

                @Override
                public Enumeration getHeaders(String name) {
                    ArrayList list = Collections.list(super.getHeaders(name));
                    if (name.equalsIgnoreCase("content-type")) {
                        list.add(contentType);
                    }

                    if (list.size() == 0 && name.startsWith("X-Atmosphere")) {
                        if (request().getAttribute(name) != null) {
                            list.add(request().getAttribute(name));
                        }
                    }

                    return Collections.enumeration(list);
                }

                public Enumeration<String> getHeaderNames() {
                    ArrayList list = Collections.list(super.getHeaderNames());
                    list.add("content-type");

                    Enumeration e = request().getAttributeNames();
                    while (e.hasMoreElements()) {
                        String name = e.nextElement().toString();
                        if (name.startsWith("X-Atmosphere")) {
                            list.add(name);
                        }
                    }

                    return Collections.enumeration(list);
                }

                @Override
                public String getHeader(String s) {
                    if (s.equalsIgnoreCase("Connection")) {
                        return "keep-alive";
                    } else if ("content-type".equalsIgnoreCase(s)) {
                        return contentType;
                    } else {
                        String name = super.getHeader(s);
                        if (name != null) {
                            if (name.startsWith("X-Atmosphere")) {
                                return (String) request().getAttribute(s);
                            }
                        }
                        return name;
                    }
                }

                @Override
                public ServletInputStream getInputStream() throws IOException {
                    return bis;
                }

                @Override
                public BufferedReader getReader() throws IOException {
                    return br;
                }

            }, new WebSocketHttpServletResponse<WebSocket>(webSocketSupport()));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
        } catch (ServletException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public void broadcast(final byte[] data, final int offset, final int length) {
        try {
            atmosphereServlet().doCometSupport(new HttpServletRequestWrapper(request()) {

                private ByteInputStream bis = new ByteInputStream(data, offset, length);
                private BufferedReader br = new BufferedReader(new StringReader(new String(data, "UTF-8")));

                @Override
                public String getMethod() {
                    return methodType;
                }

                @Override
                public String getContentType() {
                    return contentType;
                }

                @Override
                public Enumeration getHeaders(String name) {
                    ArrayList list = Collections.list(super.getHeaders(name));
                    if (name.equalsIgnoreCase("content-type")) {
                        list.add(contentType);
                    }

                    if (list.size() == 0 && name.startsWith("X-Atmosphere")) {
                        if (request().getAttribute(name) != null) {
                            list.add(request().getAttribute(name));
                        }
                    }
                    return Collections.enumeration(list);
                }

                public Enumeration<String> getHeaderNames() {
                    ArrayList list = Collections.list(super.getHeaderNames());
                    list.add("content-type");

                    Enumeration e = request().getAttributeNames();
                    while (e.hasMoreElements()) {
                        String name = e.nextElement().toString();
                        if (name.startsWith("X-Atmosphere")) {
                            list.add(name);
                        }
                    }

                    return Collections.enumeration(list);
                }

                @Override
                public String getHeader(String s) {
                    if (s.equalsIgnoreCase("Connection")) {
                        return "keep-alive";
                    } else if ("content-type".equalsIgnoreCase(s)) {
                        return contentType;
                    } else {
                        String name = super.getHeader(s);
                        if (name != null) {
                            if (name.startsWith("X-Atmosphere")) {
                                return (String) request().getAttribute(s);
                            }
                        }
                        return name;
                    }
                }

                @Override
                public ServletInputStream getInputStream() throws IOException {
                    return bis;
                }

                @Override
                public BufferedReader getReader() throws IOException {
                    return br;
                }

            }, new WebSocketHttpServletResponse<WebSocket>(webSocketSupport()));
        } catch (IOException e) {
            logger.warn(e.getMessage(), e);
        } catch (ServletException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    public static class ByteInputStream extends ServletInputStream {

        private final ByteArrayInputStream bis;

        public ByteInputStream(byte[] data, int offset, int length) {
            this.bis = new ByteArrayInputStream(data, offset, length);
        }

        @Override
        public int read() throws IOException {
            return bis.read();
        }
    }

}

