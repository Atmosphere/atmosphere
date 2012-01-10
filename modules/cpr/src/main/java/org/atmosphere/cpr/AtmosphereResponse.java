/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.atmosphere.cpr;


import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wrapper around an {@link HttpServletResponse} which use an instance of {@link org.atmosphere.websocket.WebSocket}
 * as a writer.
 *
 * @param <A>
 */
public class AtmosphereResponse<A extends AsyncIOWriter> extends HttpServletResponseWrapper {

    private final List<Cookie> cookies = new ArrayList<Cookie>();
    private final Map<String, String> headers = new HashMap<String, String>();
    private A asyncIOWriter;
    private int status = 200;
    private String statusMessage = "";
    private String charSet = "UTF-8";
    private long contentLength = -1;
    private String contentType = "txt/html";
    private boolean isCommited = false;
    private Locale locale;
    private AsyncProtocol asyncProtocol;
    private boolean headerHandled = false;
    private HttpServletRequest atmosphereRequest;
    private static final DummyHttpServletResponse dsr = new DummyHttpServletResponse();

    public AtmosphereResponse(A asyncIOWriter, AsyncProtocol asyncProtocol, HttpServletRequest atmosphereRequest) {
        super(dsr);
        this.asyncIOWriter = asyncIOWriter;
        this.asyncProtocol = asyncProtocol;
        this.atmosphereRequest = atmosphereRequest;
    }

    public AtmosphereResponse(HttpServletResponse r, A asyncIOWriter, AsyncProtocol asyncProtocol, HttpServletRequest atmosphereRequest) {
        super(r);
        this.asyncIOWriter = asyncIOWriter;
        this.asyncProtocol = asyncProtocol;
        this.atmosphereRequest = atmosphereRequest;
    }

    public void destroy(){
        cookies.clear();
        headers.clear();
        atmosphereRequest = null;
        asyncIOWriter = null;
        asyncProtocol = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addCookie(Cookie cookie) {
        cookies.add(cookie);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsHeader(String name) {
        return headers.get(name) == null ? false : true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendError(int sc, String msg) throws IOException {
        setStatus(sc,msg);
        asyncIOWriter.writeError(sc, msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendError(int sc) throws IOException {
        setStatus(sc);
        asyncIOWriter.writeError(sc, "");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendRedirect(String location) throws IOException {
        asyncIOWriter.redirect(location);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDateHeader(String name, long date) {
        headers.put(name, String.valueOf(date));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addDateHeader(String name, long date) {
        headers.put(name, String.valueOf(date));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setIntHeader(String name, int value) {
        headers.put(name, String.valueOf(value));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addIntHeader(String name, int value) {
        headers.put(name, String.valueOf(value));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStatus(int status, String statusMessage) {
        this.statusMessage = statusMessage;
        this.status = status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, String> headers() {
        if (!headerHandled) {
            for (Cookie c : cookies) {
                headers.put("Set-Cookie", c.toString());
            }
            headerHandled = false;
        }
        return headers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getHeaders(String name) {
        ArrayList<String> s = new ArrayList<String>();
        s.add(headers.get(name));
        return Collections.unmodifiableList(s);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableSet(headers.keySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCharacterEncoding(String charset) {
        this.charSet = charSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCharacterEncoding() {
        return charSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return new ServletOutputStream() {

            public void write(int i) throws java.io.IOException {
                if (asyncProtocol.inspectResponse()) {
                    asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new byte[]{(byte) i}, 0, 1));
                } else {
                    asyncIOWriter.write(new byte[]{(byte) i});
                }
            }


            public void write(byte[] bytes) throws java.io.IOException {
                if (asyncProtocol.inspectResponse()) {
                    asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, bytes, 0, bytes.length));
                } else {
                    asyncIOWriter.write(bytes);
                }
            }

            public void write(byte[] bytes, int start, int offset) throws java.io.IOException {
                if (asyncProtocol.inspectResponse()) {
                    byte[] b = asyncProtocol.handleResponse(AtmosphereResponse.this, bytes, start, offset);
                    asyncIOWriter.write(b, 0, b.length);
                } else {
                    asyncIOWriter.write(bytes, start, offset);
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PrintWriter getWriter() throws IOException {
        return new PrintWriter(getOutputStream()) {
            public void write(char[] chars, int offset, int lenght) {
                try {
                    if (asyncProtocol.inspectResponse()) {
                        asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(chars, offset, lenght)));
                    } else {
                        asyncIOWriter.write(new String(chars, offset, lenght));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            public void write(char[] chars) {
                try {
                    if (asyncProtocol.inspectResponse()) {
                        asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(chars)));
                    } else {
                        asyncIOWriter.write(new String(chars));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            public void write(String s, int offset, int lenght) {
                try {
                    if (asyncProtocol.inspectResponse()) {
                        asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(s.substring(offset, lenght))));
                    } else {
                        asyncIOWriter.write(new String(s.substring(offset, lenght)));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            public void write(java.lang.String s) {
                try {
                    if (asyncProtocol.inspectResponse()) {
                        asyncIOWriter.write(asyncProtocol.handleResponse(AtmosphereResponse.this, new String(s)));
                    } else {
                        asyncIOWriter.write(new String(s));
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContentLength(int len) {
        contentLength = len;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getContentType() {
        return contentType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCommitted() {
        return isCommited;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Locale getLocale() {
        return locale;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isWrapperFor(ServletResponse wrapped) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isWrapperFor(Class wrappedType) {
        return false;
    }

    /**
     * Return the underlying {@link org.atmosphere.websocket.WebSocket}
     *
     * @return A
     */
    public A getasyncIOWriter() {
        return asyncIOWriter;
    }

    /**
     * Return the associated {@link HttpServletRequest}
     *
     * @return the associated {@link HttpServletRequest}
     */
    public HttpServletRequest getRequest() {
        return atmosphereRequest;
    }

    public void close() throws IOException {
        if (asyncIOWriter != null) {
            asyncIOWriter.close();
        }
    }


    private final static class DummyHttpServletResponse implements HttpServletResponse {
        public void addCookie(Cookie cookie) {
        }

        public boolean containsHeader(String name) {
            return false;
        }

        public String encodeURL(String url) {
            return null;
        }

        public String encodeRedirectURL(String url) {
            return null;
        }

        public String encodeUrl(String url) {
            return null;
        }

        public String encodeRedirectUrl(String url) {
            return null;
        }

        public void sendError(int sc, String msg) throws IOException {
        }

        public void sendError(int sc) throws IOException {

        }

        public void sendRedirect(String location) throws IOException {

        }

        public void setDateHeader(String name, long date) {

        }

        public void addDateHeader(String name, long date) {

        }

        public void setHeader(String name, String value) {

        }

        public void addHeader(String name, String value) {

        }

        public void setIntHeader(String name, int value) {

        }

        public void addIntHeader(String name, int value) {

        }

        public void setStatus(int sc) {

        }

        public void setStatus(int sc, String sm) {
        }

        public int getStatus() {
            return 0;
        }

        public String getHeader(String name) {
            return null;
        }

        public Collection<String> getHeaders(String name) {
            return null;
        }

        public Collection<String> getHeaderNames() {
            return null;
        }

        public String getCharacterEncoding() {
            return null;
        }

        public String getContentType() {
            return null;
        }

        public ServletOutputStream getOutputStream() throws IOException {
            return null;
        }

        public PrintWriter getWriter() throws IOException {
            return null;
        }

        public void setCharacterEncoding(String charset) {

        }

        public void setContentLength(int len) {

        }

        public void setContentType(String type) {

        }

        public void setBufferSize(int size) {

        }

        public int getBufferSize() {
            return 0;
        }

        public void flushBuffer() throws IOException {

        }

        public void resetBuffer() {

        }

        public boolean isCommitted() {
            return false;
        }

        public void reset() {
        }

        public void setLocale(Locale loc) {
        }

        public Locale getLocale() {
            return null;
        }
    }
}
