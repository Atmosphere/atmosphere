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
package org.atmosphere.cpr;

import org.atmosphere.websocket.WebSocketHttpServletResponse;
import org.atmosphere.websocket.WebSocketSupport;
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
 * Like the {@link AsynchronousProcessor} class, this class is responsible for dispatching WebSocket request to the
 * proper {@link org.atmosphere.websocket.WebSocketSupport} implementation by wrapping the Websocket bytes within
 * an {@link javax.servlet.http.HttpServletRequest}.
 * <p/>
 * The current content-type is text/plain for String message, and application/octet-stream for bytes.
 * <p/>
 * TODO: Add a way to configure the content-type.
 *
 * @author Jeanfrancois Arcand
 */
public class HttpServletRequestWebSocketProcessor extends WebSocketProcessor implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);

    public HttpServletRequestWebSocketProcessor(AtmosphereServlet atmosphereServlet, WebSocketSupport webSocketSupport) {
        super(atmosphereServlet, webSocketSupport);
    }

    public void broadcast(final String data) {
        try {
            atmosphereServlet().doCometSupport(new HttpServletRequestWrapper(request()) {

                private ByteInputStream bis = new ByteInputStream(data.getBytes(), 0, data.getBytes().length);
                private BufferedReader br = new BufferedReader(new StringReader(data));

                @Override
                public String getMethod() {
                    return "POST";
                }

                @Override
                public String getContentType() {
                    return "text/plain";
                }

                @Override
                public Enumeration getHeaders(String name) {
                    ArrayList list = Collections.list(super.getHeaders(name));
                    if (name.equalsIgnoreCase("content-type")) {
                        list.add("text/plain");
                    }
                    return Collections.enumeration(list);
                }

                public Enumeration<String> getHeaderNames() {
                    ArrayList list = Collections.list(super.getHeaderNames());
                    list.add("content-type");
                    return Collections.enumeration(list);
                }

                @Override
                public String getHeader(String s) {
                    if (s.equalsIgnoreCase("Connection")) {
                        return "keep-alive";
                    } else if ("content-type".equalsIgnoreCase(s)) {
                        return "text/plain";
                    } else {
                        return super.getHeader(s);
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

            }, new WebSocketHttpServletResponse<WebSocketSupport>(webSocketSupport()));
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
                    return "POST";
                }

                @Override
                public String getContentType() {
                    return "application/octet-stream";
                }

                @Override
                public Enumeration getHeaders(String name) {
                    ArrayList list = Collections.list(super.getHeaders(name));
                    if (name.equalsIgnoreCase("content-type")) {
                        list.add("application/octet-stream");
                    }
                    return Collections.enumeration(list);
                }

                public Enumeration<String> getHeaderNames() {
                    ArrayList list = Collections.list(super.getHeaderNames());
                    list.add("content-type");
                    return Collections.enumeration(list);
                }

                @Override
                public String getHeader(String s) {
                    if (s.equalsIgnoreCase("Connection")) {
                        return "keep-alive";
                    } else if ("content-type".equalsIgnoreCase(s)) {
                        return "application/octet-stream";
                    } else {
                        return super.getHeader(s);
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

            }, new WebSocketHttpServletResponse<WebSocketSupport>(webSocketSupport()));
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

