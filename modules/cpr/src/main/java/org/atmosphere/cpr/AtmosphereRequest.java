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

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE;

/**
 * A Builder for constructing {@link HttpServletRequest}
 */
public class AtmosphereRequest extends HttpServletRequestWrapper {

    private final ByteInputStream bis;
    private final BufferedReader br;
    private final String pathInfo;
    private final Map<String, String> headers;
    private final Map<String, String[]> queryStrings;
    private final String methodType;
    private final String contentType;
    private final HttpServletRequest request;
    private final String servletPath;
    private final String requestURI;
    private final String requestURL;
    private final Map<String, Object> localAttributes;

    private AtmosphereRequest(Builder b) {
        super(b.request);
        pathInfo = b.pathInfo == null ? b.request.getPathInfo() : b.pathInfo;
        request = b.request;
        headers = b.headers == null ? new HashMap<String, String>() : b.headers;
        queryStrings = b.queryStrings == null ? new HashMap<String, String[]>() : b.queryStrings;
        servletPath = b.servletPath;
        requestURI = b.requestURI;
        requestURL = b.requestURL;
        localAttributes = b.localAttributes;

        if (b.dataBytes != null) {
            bis = new ByteInputStream(b.dataBytes, b.offset, b.length);
            try {
                br = new BufferedReader(new StringReader(new String(b.dataBytes, b.offset, b.length, b.encoding)));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        } else if (b.data != null) {
            bis = new ByteInputStream(b.data.getBytes(), 0, b.data.getBytes().length);
            br = new BufferedReader(new StringReader(b.data));
        } else {
            bis = null;
            br = null;
        }
        methodType = b.methodType == null ? (request != null ? request.getMethod() : "GET") : b.methodType;
        contentType = b.contentType == null ? (request != null ? request.getContentType() : "text/plain") : b.contentType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPathInfo() {
        return pathInfo;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMethod() {
        return methodType;
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
    public String getServletPath() {
        return servletPath != null ? servletPath : super.getServletPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRequestURI() {
        return requestURI != null ? requestURI : (request != null ? super.getRequestURI() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StringBuffer getRequestURL() {
        return requestURL != null ? new StringBuffer(requestURL) : (request != null ? request.getRequestURL() : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration getHeaders(String name) {
        ArrayList list = Collections.list(super.getHeaders(name));
        if (name.equalsIgnoreCase("content-type")) {
            list.add(contentType);
        }

        if (headers.get(name) != null) {
            list.add(headers.get(name));
        }

        if (request != null) {
            if (list.size() == 0 && name.startsWith(X_ATMOSPHERE)) {
                if (request.getAttribute(name) != null) {
                    list.add(request.getAttribute(name));
                }
            }
        }
        return Collections.enumeration(list);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getHeaderNames() {
        ArrayList list = Collections.list(super.getHeaderNames());
        list.add("content-type");

        if (request != null) {
            Enumeration e = request.getAttributeNames();
            while (e.hasMoreElements()) {
                String name = e.nextElement().toString();
                if (name.startsWith(X_ATMOSPHERE)) {
                    list.add(name);
                }
            }
        }

        list.addAll(headers.keySet());

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
            if (name == null) {
                if (headers.get(s) != null) {
                    return headers.get(s);
                }

                if (s.startsWith(X_ATMOSPHERE) && request != null) {
                    return (String) request.getAttribute(s);
                }
            }
            return name;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getParameter(String s) {
        String name = super.getParameter(s);
        if (name == null) {
            if (queryStrings.get(s) != null) {
                return queryStrings.get(s)[0];
            }
        }
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> m = (request != null ? request.getParameterMap() : Collections.<String, String[]>emptyMap());
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            String[] s = queryStrings.get(e.getKey());
            if (s != null) {
                String[] s1 = new String[s.length + e.getValue().length];
                System.arraycopy(s, 0, s1, 0, s.length);
                System.arraycopy(s1, s.length + 1, e.getValue(), 0, e.getValue().length);
                queryStrings.put(e.getKey(), s1);
            } else {
                queryStrings.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(queryStrings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] getParameterValues(String s) {
        String[] list = super.getParameterValues(s) == null ? new String[0] : super.getParameterValues(s);
        if (queryStrings.get(s) != null) {
            String[] newList = queryStrings.get(s);
            String[] s1 = new String[list.length + newList.length];
            System.arraycopy(list, 0, s1, 0, list.length);
            System.arraycopy(s1, list.length + 1, newList, 0, newList.length);
        }
        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ServletInputStream getInputStream() throws IOException {
        return bis == null ? (request != null ? request.getInputStream() : null) : bis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BufferedReader getReader() throws IOException {
        return br == null ? (request != null ? request.getReader() : null) : br;
    }

    private final static class ByteInputStream extends ServletInputStream {

        private final ByteArrayInputStream bis;

        public ByteInputStream(byte[] data, int offset, int length) {
            this.bis = new ByteArrayInputStream(data, offset, length);
        }

        @Override
        public int read() throws IOException {
            return bis.read();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAttribute(String s, Object o) {
        localAttributes.put(s, o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object getAttribute(String s) {
        return localAttributes.get(s) != null ? localAttributes.get(s) : (request != null ? request.getAttribute(s) : null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAttribute(String name) {
        if (localAttributes.remove(name) == null && request != null) {
            request.removeAttribute(name);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Enumeration<String> getAttributeNames() {
        Map<String, Object> m = new HashMap<String, Object>();
        m.putAll(localAttributes);

        Enumeration<String> e = (request != null ? request.getAttributeNames() : null);
        if (e != null) {
            String s;
            while (e.hasMoreElements()) {
                s = e.nextElement();
                m.put(s, request.getAttribute(s));
            }
        }
        return Collections.enumeration(m.keySet());
    }

    public final static class Builder {

        private HttpServletRequest request;
        private String pathInfo;
        private byte[] dataBytes;
        private int offset;
        private int length;
        private String encoding = "UTF-8";
        private String methodType;
        private String contentType;
        private String data;
        private Map<String, String> headers;
        private Map<String, String[]> queryStrings;
        private String servletPath;
        private String requestURI;
        private String requestURL;
        private Map<String, Object> localAttributes = new HashMap<String, Object>();

        public Builder() {
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            localAttributes = attributes;
            return this;
        }

        public Builder request(HttpServletRequest request) {
            this.request = request;
            return this;
        }

        public Builder servletPath(String servletPath) {
            this.servletPath = servletPath;
            return this;
        }

        public Builder requestURI(String requestURI) {
            this.requestURI = requestURI;
            return this;
        }

        public Builder requestURL(String requestURL) {
            this.requestURL = requestURL;
            return this;
        }

        public Builder pathInfo(String pathInfo) {
            this.pathInfo = pathInfo;
            return this;
        }

        public Builder body(byte[] dataBytes, int offset, int length) {
            this.dataBytes = dataBytes;
            this.offset = offset;
            this.length = length;
            return this;
        }

        public Builder encoding(String encoding) {
            this.encoding = encoding;
            return this;
        }

        public Builder method(String methodType) {
            this.methodType = methodType;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder body(String data) {
            this.data = data;
            return this;
        }

        public AtmosphereRequest build() {
            return new AtmosphereRequest(this);
        }

        public Builder queryStrings(Map<String, String[]> queryStrings) {
            this.queryStrings = queryStrings;
            return this;
        }
    }

}
