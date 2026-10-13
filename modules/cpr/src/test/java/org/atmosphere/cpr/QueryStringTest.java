/*
 * Copyright 2008-2026 Async-IO.org
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

import org.atmosphere.container.BlockingIOCometSupport;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class QueryStringTest {

    private AtmosphereFramework framework;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @Test
    public void basicQueryStringTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<String> q = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                q.set(resource.getRequest().getQueryString());
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });
        // use ordered map to keep ordering in map see https://github.com/Atmosphere/atmosphere/issues/1652
        Map<String, String[]> queryStrings = new LinkedHashMap<String, String[]>();
        queryStrings.put("a", new String[]{"b"});
        queryStrings.put("b", new String[]{"d"});
        queryStrings.put("c", new String[]{"f"});

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryStrings(queryStrings).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(q.get());
        assertEquals("a=b&b=d&c=f", q.get());
    }

    @Test
    public void issue1321() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<String> q = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                q.set(resource.getRequest().getQueryString());
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });
        String s = "&X-Atmosphere-tracking-id=c8834462-c46e-4dad-a22f-b86aabe3f883&X-Atmosphere-Framework=2.0.3-javascript&X-Atmosphere-Transport=long-polling&X-Atmosphere-TrackMessageSize=true&X-atmo-protocol=true&_=1380799455333";

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(s).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(q.get());
        assertEquals("", q.get());
    }

    @Test
    public void issue1421() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
        final AtomicReference<String> q = new AtomicReference<String>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                q.set(resource.getRequest().getContentType());
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            }

            @Override
            public void destroy() {
            }
        });
        String s = "&Content-Type=text/x-gwt-rpc;%20charset=UTF-8&X-Atmosphere-tracking-id=c8834462-c46e-4dad-a22f-b86aabe3f883&X-Atmosphere-Framework=2.0.3-javascript&X-Atmosphere-Transport=long-polling&X-Atmosphere-TrackMessageSize=true&X-atmo-protocol=true&_=1380799455333";

        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(s).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(q.get());
        assertEquals("text/x-gwt-rpc; charset=UTF-8", q.get());
    }

    private static final String TRACKING_ID = "c8834462-c46e-4dad-a22f-b86aabe3f883";

    private AtmosphereRequest configure(String queryString) throws IOException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().queryString(queryString).pathInfo("/a").build();
        framework.configureRequestResponse(request, AtmosphereResponseImpl.newInstance());
        return request;
    }

    @Test
    public void queryParametersMappedToHeadersArePercentDecoded() throws IOException {
        AtmosphereRequest request = configure("X-Atmosphere-tracking-id=" + TRACKING_ID
                + "&X-Atmosphere-Transport=long-polling"
                + "&X-Custom=a%20b%26c%3Dd"
                + "&X-Name=caf%C3%A9%20%E2%82%AC"
                + "&X-Atmosphere-Auth=ab%2Bc%2Fd%3D%3D"
                + "&X%2DEncoded=name");

        assertEquals("a b&c=d", request.getHeader("X-Custom"));
        assertEquals("caf\u00E9 \u20AC", request.getHeader("X-Name"));
        assertEquals("ab+c/d==", request.getHeader("X-Atmosphere-Auth"));
        assertEquals("name", request.getHeader("X-Encoded"));
        assertEquals(TRACKING_ID, request.getHeader("X-Atmosphere-tracking-id"));
        assertEquals("long-polling", request.getHeader("X-Atmosphere-Transport"));
    }

    @Test
    public void plusIsLiteralEncodedOrNot() throws IOException {
        AtmosphereRequest request = configure("X-Encoded-Plus=a%2Bb&X-Bare-Plus=a+b&X-Mixed=a+b%20c");

        assertEquals("a+b", request.getHeader("X-Encoded-Plus"));
        assertEquals("a+b", request.getHeader("X-Bare-Plus"));
        assertEquals("a+b c", request.getHeader("X-Mixed"));
    }

    @Test
    public void malformedEscapeKeepsRawValueAndLaterParameters() throws IOException {
        AtmosphereRequest request = configure("Content-Type=text%zz&X-Bad=%zz&X-Truncated=a%2&X-After=x%20y");

        assertEquals("text%zz", request.getContentType());
        assertEquals("%zz", request.getHeader("X-Bad"));
        assertEquals("a%2", request.getHeader("X-Truncated"));
        assertEquals("x y", request.getHeader("X-After"));
        // The rebuilt query string keeps every parameter as sent; only the malformed ones are not parameters.
        assertEquals("X-Bad=%zz&X-Truncated=a%2&X-After=x%20y", request.getQueryString());
        assertEquals("x y", request.getParameter("X-After"));
        assertNull(request.getParameter("X-Bad"));
        assertNull(request.getParameter("X-Truncated"));
    }

    @Test
    public void postBodyIsDecodedOnceAsTheBody() throws IOException {
        AtmosphereRequest request = configure("X-Atmosphere-tracking-id=" + TRACKING_ID
                + "&X-Atmosphere-Post-Body=100%2525%20sure");

        assertEquals("POST", request.getMethod());
        assertEquals("100%25 sure", request.body().asString());
        assertNull(request.getHeader("X-Atmosphere-Post-Body"));
    }

    @Test
    public void rebuiltQueryStringKeepsTheEncodedForm() throws IOException {
        AtmosphereRequest request = configure("X-Atmosphere-tracking-id=" + TRACKING_ID + "&q=a%20b%26c&_=1");

        assertEquals("q=a%20b%26c", request.getQueryString());
        assertEquals("a b&c", request.getParameter("q"));
        assertEquals("a b&c", request.getHeader("q"));
    }

    @Test
    public void decodingCanBeDisabled() throws IOException {
        framework.addInitParameter(ApplicationConfig.DECODE_QUERYSTRING_HEADERS, "false");
        AtmosphereRequest request = configure("X-Custom=a%20b&X-Atmosphere-Post-Body=a%2520b"
                + "&Content-Type=text/plain;%20charset=UTF-8");

        assertEquals("a%20b", request.getHeader("X-Custom"));
        assertEquals("a%20b", request.body().asString());
        assertEquals("text/plain; charset=UTF-8", request.getContentType());
    }
}
