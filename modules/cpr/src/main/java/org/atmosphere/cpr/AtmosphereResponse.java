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
package org.atmosphere.cpr;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * An Atmosphere's response representation. An AtmosphereResponse can be used to construct a bi-directional asynchronous
 * application. If the underlying transport is a WebSocket or if its associated {@link AtmosphereResource} has been
 * suspended, this object can be used to write message back to the client at any moment.
 * <br/>
 * This object can delegate the write operation to {@link AsyncIOWriter}.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResponse extends HttpServletResponse {
    void destroy();

    void destroy(boolean force);

    boolean destroyed();

    @Override
    void addCookie(Cookie cookie);

    @Override
    boolean containsHeader(String name);

    @Override
    String encodeURL(String url);

    @Override
    String encodeRedirectURL(String url);

    @Override
    String encodeUrl(String url);

    @Override
    String encodeRedirectUrl(String url);

    AtmosphereResponse delegateToNativeResponse(boolean delegateToNativeResponse);

    @Override
    void sendError(int sc, String msg) throws IOException;

    @Override
    void sendError(int sc) throws IOException;

    @Override
    void sendRedirect(String location) throws IOException;

    @Override
    void setDateHeader(String name, long date);

    @Override
    void addDateHeader(String name, long date);

    @Override
    void setHeader(String name, String value);

    @Override
    void addHeader(String name, String value);

    @Override
    void setIntHeader(String name, int value);

    @Override
    void addIntHeader(String name, int value);

    @Override
    void setStatus(int status);

    @Override
    void setStatus(int status, String statusMessage);

    @Override
    int getStatus();

    ServletResponse getResponse();

    String getStatusMessage();

    Map<String, String> headers();

    @Override
    String getHeader(String name);

    @Override
    Collection<String> getHeaders(String name);

    @Override
    Collection<String> getHeaderNames();

    @Override
    void setCharacterEncoding(String charSet);

    @Override
    void flushBuffer() throws IOException;

    @Override
    int getBufferSize();

    @Override
    String getCharacterEncoding();

    /**
     * Check if this object can be destroyed. Default is true.
     */
    boolean isDestroyable();

    AtmosphereResponse destroyable(boolean destroyable);

    @Override
    ServletOutputStream getOutputStream() throws IOException;

    @Override
    PrintWriter getWriter() throws IOException;

    @Override
    void setContentLength(int len);

    @Override
    void setContentType(String contentType);

    @Override
    String getContentType();

    @Override
    boolean isCommitted();

    @Override
    void reset();

    @Override
    void resetBuffer();

    @Override
    void setBufferSize(int size);

    @Override
    void setLocale(Locale locale);

    @Override
    Locale getLocale();

    /**
     * Return the underlying {@link AsyncIOWriter}.
     */
    AsyncIOWriter getAsyncIOWriter();

    /**
     * Set an implementation of {@link AsyncIOWriter} that will be invoked every time a write operation is ready to be
     * processed.
     *
     * @param asyncIOWriter of {@link AsyncIOWriter}
     * @return this
     */
    AtmosphereResponse asyncIOWriter(AsyncIOWriter asyncIOWriter);

    /**
     * Return the associated {@link AtmosphereRequest}.
     *
     * @return the associated {@link AtmosphereRequest}
     */
    AtmosphereRequest request();

    /**
     * Set the associated {@link AtmosphereRequest}.
     *
     * @param atmosphereRequest a {@link AtmosphereRequest}
     * @return this
     */
    AtmosphereResponse request(AtmosphereRequest atmosphereRequest);

    /**
     * Close the associated {@link AsyncIOWriter}.
     *
     * @throws IOException
     */
    void close() throws IOException;

    /**
     * Close the associated {@link PrintWriter} or {@link java.io.OutputStream}
     */
    void closeStreamOrWriter();

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}
     *
     * @param data the String to write
     */
    AtmosphereResponse write(String data);

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}. If writeUsingOriginalResponse is
     * set to true, execute the write without invoking the defined {@link AsyncIOWriter}.
     *
     * @param data                       the String to write
     * @param writeUsingOriginalResponse if true, execute the write without invoking the {@link AsyncIOWriter}
     */
    AtmosphereResponse write(String data, boolean writeUsingOriginalResponse);

    /**
     * Write the bytes by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}.
     *
     * @param data the bytes to write
     */
    AtmosphereResponse write(byte[] data);

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is based
     * on the request attribute  {@link ApplicationConfig#PROPERTY_USE_STREAM}. If writeUsingOriginalResponse is set to
     * true, execute the write without invoking the defined {@link AsyncIOWriter}.
     *
     * @param data                       the bytes to write
     * @param writeUsingOriginalResponse if true, execute the write without invoking the {@link AsyncIOWriter}
     */
    AtmosphereResponse write(byte[] data, boolean writeUsingOriginalResponse);

    /**
     * Write the bytes by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is
     * based on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}.
     *
     * @param data   the bytes to write
     * @param offset the first byte position to write
     * @param length the data length
     */
    AtmosphereResponse write(byte[] data, int offset, int length);

    /**
     * Write the String by either using the {@link PrintWriter} or {@link java.io.OutputStream}. The decision is based
     * on the request attribute {@link ApplicationConfig#PROPERTY_USE_STREAM}. If writeUsingOriginalResponse is set to
     * true, execute the write without invoking the defined {@link AsyncIOWriter}.
     *
     * @param data                       the bytes to write
     * @param offset                     the first byte position to write
     * @param length                     the data length
     * @param writeUsingOriginalResponse if true, execute the write without invoking the {@link AsyncIOWriter}
     */
    AtmosphereResponse write(byte[] data, int offset, int length, boolean writeUsingOriginalResponse);

    /**
     * The {@link AtmosphereResource} associated with this request. If the request hasn't been suspended, this
     * method will return null.
     *
     * @return an {@link AtmosphereResource}, or null.
     */
    AtmosphereResource resource();

    void setResponse(ServletResponse response);

    /**
     * Return the {@link AtmosphereResource#uuid()} used by this object.
     *
     * @return the {@link AtmosphereResource#uuid()} used by this object.
     */
    String uuid();

    @Override
    String toString();

    interface Builder {
        Builder destroyable(boolean isRecyclable);

        Builder asyncIOWriter(AsyncIOWriter asyncIOWriter);

        Builder status(int status);

        Builder statusMessage(String statusMessage);

        Builder request(AtmosphereRequest atmosphereRequest);

        AtmosphereResponse build();

        Builder header(String name, String value);

        Builder writeHeader(boolean writeStatusAndHeader);

        Builder response(HttpServletResponse res);
    }
}
