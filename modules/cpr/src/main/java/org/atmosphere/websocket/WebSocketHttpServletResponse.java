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
package org.atmosphere.websocket;

import org.atmosphere.util.LoggerUtils;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Wrapper around an {@link HttpServletResponse} which use an instance of {@link WebSocketSupport}
 * as a writer.
 *
 * @param <A>
 */
public class WebSocketHttpServletResponse<A extends WebSocketSupport> extends HttpServletResponseWrapper {

    private final ArrayList<Cookie> cookies = new ArrayList<Cookie>();
    private final HashMap<String, String> headers = new HashMap<String, String>();
    private final A webSocketSupport;
    private int status = 200;
    private String statusMessage = "";
    private String charSet = "UTF-8";
    private byte frame;
    private long contentLength = -1;
    private String contentType = "txt/html";
    private boolean isCommited = false;
    private Locale locale;

    public WebSocketHttpServletResponse(A webSocketSupport) {
        super(new HttpServletResponse() {

            public void addCookie(Cookie cookie) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public boolean containsHeader(String name) {
                return false;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String encodeURL(String url) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String encodeRedirectURL(String url) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String encodeUrl(String url) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String encodeRedirectUrl(String url) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public void sendError(int sc, String msg) throws IOException {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void sendError(int sc) throws IOException {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void sendRedirect(String location) throws IOException {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setDateHeader(String name, long date) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void addDateHeader(String name, long date) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setHeader(String name, String value) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void addHeader(String name, String value) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setIntHeader(String name, int value) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void addIntHeader(String name, int value) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setStatus(int sc) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setStatus(int sc, String sm) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public int getStatus() {
                return 0;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String getHeader(String name) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public Collection<String> getHeaders(String name) {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public Collection<String> getHeaderNames() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String getCharacterEncoding() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public String getContentType() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public ServletOutputStream getOutputStream() throws IOException {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public PrintWriter getWriter() throws IOException {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setCharacterEncoding(String charset) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setContentLength(int len) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setContentType(String type) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setBufferSize(int size) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public int getBufferSize() {
                return 0;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public void flushBuffer() throws IOException {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void resetBuffer() {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public boolean isCommitted() {
                return false;  //To change body of implemented methods use File | Settings | File Templates.
            }

            public void reset() {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public void setLocale(Locale loc) {
                //To change body of implemented methods use File | Settings | File Templates.
            }

            public Locale getLocale() {
                return null;  //To change body of implemented methods use File | Settings | File Templates.
            }
        });
        this.webSocketSupport = webSocketSupport;
    }

    /**
     * {@inheritDoc}
     */
    public void addCookie(Cookie cookie) {
        cookies.add(cookie);
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsHeader(String name) {
        return headers.get(name) == null ? false : true;
    }

    /**
     * {@inheritDoc}
     */
    public String encodeURL(String url) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String encodeRedirectURL(String url) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String encodeUrl(String url) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public String encodeRedirectUrl(String url) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void sendError(int sc, String msg) throws IOException {
        webSocketSupport.writeError(sc, msg);
    }

    /**
     * {@inheritDoc}
     */
    public void sendError(int sc) throws IOException {
        webSocketSupport.writeError(sc, "");
    }

    /**
     * {@inheritDoc}
     */
    public void sendRedirect(String location) throws IOException {
        webSocketSupport.redirect(location);
    }

    /**
     * {@inheritDoc}
     */
    public void setDateHeader(String name, long date) {
        headers.put(name, String.valueOf(date));
    }

    /**
     * {@inheritDoc}
     */
    public void addDateHeader(String name, long date) {
        headers.put(name, String.valueOf(date));
    }

    /**
     * {@inheritDoc}
     */
    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    /**
     * {@inheritDoc}
     */
    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    /**
     * {@inheritDoc}
     */
    public void setIntHeader(String name, int value) {
        headers.put(name, String.valueOf(value));
    }

    /**
     * {@inheritDoc}
     */
    public void addIntHeader(String name, int value) {
        headers.put(name, String.valueOf(value));
    }

    /**
     * {@inheritDoc}
     */
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * {@inheritDoc}
     */
    public void setStatus(int status, String statusMessage) {
        this.statusMessage = statusMessage;
        this.status = status;
    }

    /**
     * {@inheritDoc}
     */
    public int getStatus() {
        return status;
    }

    /**
     * {@inheritDoc}
     */
    public String getHeader(String name) {
        return headers.get(name);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<String> getHeaders(String name) {

        ArrayList<String> s = new ArrayList<String>();
        s.add(headers.get(name));

        return Collections.unmodifiableList(s);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<String> getHeaderNames() {
        return Collections.unmodifiableSet(headers.keySet());
    }

    /**
     * {@inheritDoc}
     */
    public void setCharacterEncoding(String charset) {
        this.charSet = charSet;
    }

    /**
     * {@inheritDoc}
     */
    public String getCharacterEncoding() {
        return charSet;
    }

    /**
     * {@inheritDoc}
     */
    public ServletOutputStream getOutputStream() throws IOException {
        return new ServletOutputStream() {

            public void write(int i) throws java.io.IOException {
                webSocketSupport.write(frame, new byte[]{(byte) i});
            }


            public void write(byte[] bytes) throws java.io.IOException {
                webSocketSupport.write(frame, bytes);
            }

            public void write(byte[] bytes, int start, int offset) throws java.io.IOException {
                webSocketSupport.write(frame, bytes, start, offset);
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    public PrintWriter getWriter() throws IOException {
        return new PrintWriter(getOutputStream()) {
            public void write(char[] chars, int offset, int lenght) {
                try {
                    webSocketSupport.write(frame, new String(chars, offset, lenght));
                } catch (IOException e) {
                    LoggerUtils.getLogger().log(Level.INFO, "", e);

                }
            }

            public void write(char[] chars) {
                try {
                    webSocketSupport.write(frame, new String(chars));
                } catch (IOException e) {
                    LoggerUtils.getLogger().log(Level.INFO, "", e);

                }
            }

            public void write(String s, int offset, int lenght) {
                try {
                    webSocketSupport.write(frame, new String(s.substring(offset, lenght)));
                } catch (IOException e) {
                    LoggerUtils.getLogger().log(Level.INFO, "", e);

                }
            }

            public void write(java.lang.String s) {
                try {
                    webSocketSupport.write(frame, new String(s));
                } catch (IOException e) {
                    LoggerUtils.getLogger().log(Level.INFO, "", e);

                }
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    public void setContentLength(int len) {
        contentLength = len;
    }

    /**
     * {@inheritDoc}
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * {@inheritDoc}
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * {@inheritDoc}
     */
    public void setBufferSize(int size) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public int getBufferSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public void flushBuffer() throws IOException {
    }

    /**
     * {@inheritDoc}
     */
    public boolean isCommitted() {
        return isCommited;
    }

    /**
     * {@inheritDoc}
     */
    public void reset() {
    }

    /**
     * {@inheritDoc}
     */
    public void resetBuffer() {
    }

    /**
     * {@inheritDoc}
     */
    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    /**
     * {@inheritDoc}
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isWrapperFor(ServletResponse wrapped) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isWrapperFor(Class wrappedType) {
        return false;
    }

    /**
     * Return the underlying {@link WebSocketSupport}
     *
     * @return
     */
    public A getWebSocketSupport() {
        return webSocketSupport;
    }
}
