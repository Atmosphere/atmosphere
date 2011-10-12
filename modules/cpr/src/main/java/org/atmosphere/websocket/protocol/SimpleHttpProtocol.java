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
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE;

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

    public void parseMessage(final String d) {
        try {

            final AtomicReference<String> data = new AtomicReference<String>(d);
            final AtomicReference<String> pathInfo = new AtomicReference<String>(request().getPathInfo());
            if (data.get().startsWith(delimiter)) {
                String[] token = data.get().split(delimiter);
                pathInfo.set(token[1]);
                data.set(token[2]);
            }

            atmosphereServlet().doCometSupport(new HttpServletRequestWrapper(request()) {

                private ByteInputStream bis = new ByteInputStream(data.get().getBytes(), 0, data.get().getBytes().length);
                private BufferedReader br = new BufferedReader(new StringReader(data.get()));

                @Override
                public String getMethod() {
                    return methodType;
                }

                @Override
                public String getPathInfo() {
                    return pathInfo.get();
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

                    if (list.size() == 0 && name.startsWith(X_ATMOSPHERE)) {
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
                        if (name.startsWith(X_ATMOSPHERE)) {
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
                            if (name.startsWith(X_ATMOSPHERE)) {
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
    public void parseMessage(final byte[] d, final int offset, final int length) {
        try {
            final AtomicReference<byte[]> data = new AtomicReference<byte[]>(d);
            final AtomicReference<String> pathInfo = new AtomicReference<String>(request().getPathInfo());
            // TODO: should not do String <-> byte conversion
            if (d[0] == (byte)delimiter.charAt(0) && d[1] == (byte)delimiter.charAt(0)) {
                final String s = new String(d, offset, length, "UTF-8");
                String[] token = s.split(delimiter);
                pathInfo.set(token[1]);
                data.set(token[2].getBytes("UTF-8"));
            }
            atmosphereServlet().doCometSupport(new HttpServletRequestWrapper(request()) {

                private ByteInputStream bis = new ByteInputStream(data.get(), offset, length);
                private BufferedReader br = new BufferedReader(new StringReader(new String(d, offset, offset, "UTF-8")));

                @Override
                public String getPathInfo() {
                    return pathInfo.get();
                }

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

                    if (list.size() == 0 && name.startsWith(X_ATMOSPHERE)) {
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
                        if (name.startsWith(X_ATMOSPHERE)) {
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
                            if (name.startsWith(X_ATMOSPHERE)) {
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

