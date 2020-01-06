/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.*;
import org.atmosphere.util.IOUtils;
import org.json.JSONObject;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class SimpleRestInterceptorTest {
    private AtmosphereFramework framework;
    private AtmosphereConfig config;

    //TODO tests other part of the inteceptors

    @Test
    public void testCreateRequestNormal() throws Exception {
        setupAtmosphere();
        final String data = "{\"id\": \"123\", \"method\": \"POST\", \"path\": \"/topics/test\", "
                + "\"type\": \"application/json\", \"detached\": true}{\"records\": [{\"value\": \"S2Fma2E=\"}]}";

        AtmosphereResource resource = createAtmosphereResource("POST", "/", Collections.singletonMap(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, "0123456789"), data);
        SimpleRestInterceptor interceptor = new SimpleRestInterceptor();
        interceptor.configure(config);

        AtmosphereRequest dispatchedRequest = interceptor.createAtmosphereRequest(resource.getRequest(), IOUtils.readEntirelyAsString(resource).toString());
        assertEquals(dispatchedRequest.getMethod(), "POST");
        assertEquals(dispatchedRequest.getRequestURI(), "/topics/test");
        assertEquals(dispatchedRequest.getContentType(), "application/json");
        assertEquals(extractContent(dispatchedRequest.getReader()), "{\"records\": [{\"value\": \"S2Fma2E=\"}]}");
        assertEquals(dispatchedRequest.getHeader("X-Request-Key"), "0123456789#123");
    }

    @Test
    public void testCreateRequestContinued() throws Exception {
        setupAtmosphere();
        final String data1 = "{\"id\": \"123\", \"method\": \"POST\", \"path\": \"/topics/test\", "
                + "\"type\": \"application/json\", \"continue\": true}{\"records\": [";
        final String data2 = "{\"id\": \"123\", \"method\": \"POST\", \"path\": \"/topics/test\", "
                + "\"type\": \"application/json\"}{\"value\": \"S2Fma2E=\"}]}";

        AtmosphereResource resource1 = createAtmosphereResource("POST", "/", Collections.<String, String>emptyMap(), data1);
        AtmosphereResource resource2 = createAtmosphereResource("POST", "/", Collections.<String, String>emptyMap(), data2);
        SimpleRestInterceptor interceptor = new SimpleRestInterceptor();
        interceptor.configure(config);

        AtmosphereRequest dispatchedRequest1 = interceptor.createAtmosphereRequest(resource1.getRequest(), IOUtils.readEntirelyAsString(resource1).toString());
        assertEquals(dispatchedRequest1.getMethod(), "POST");
        assertEquals(dispatchedRequest1.getRequestURI(), "/topics/test");
        assertEquals(dispatchedRequest1.getContentType(), "application/json");

        AtmosphereRequest dispatchedRequest2 = interceptor.createAtmosphereRequest(resource1.getRequest(), IOUtils.readEntirelyAsString(resource2).toString());
        assertNull(dispatchedRequest2);

        assertEquals(extractContent(dispatchedRequest1.getReader()), "{\"records\": [{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingNoData() throws Exception {
        final String data = "{\"id\": \"123\", \"accept\" : \"text/plain\" }";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);

        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("accept", "text/plain");
        verify(jsonpart, r, expectedHeaders, "");
    }

    @Test
    public void testParsingNoDataAposMixedSpace() throws Exception {
        final String data = "{\n 'id':\"123\",'accept'\n: 'text/plain'\r }";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);

        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("accept", "text/plain");
        verify(jsonpart, r, expectedHeaders, "");
    }

    @Test
    public void testParsingNoDataNumber() throws Exception {
        final String data = "{'id': \"123\", \"size\" : 69124, 'ack' : true }";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);
        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("size", 69124);
        expectedHeaders.put("ack", true);
        verify(jsonpart, r, expectedHeaders, "");
    }

    @Test
    public void testParsingNoDataNumberMixedSpace() throws Exception {
        final String data = "{'id': \"123\", \"size\":69124, \r\n'ack' :true }";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);
        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("size", 69124);
        expectedHeaders.put("ack", true);
        verify(jsonpart, r, expectedHeaders, "");
    }

    @Test
    public void testParsingWithData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"application/json\"}{\"records\": [{\"value\": \"S2Fma2E=\"}]}";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);

        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "application/json");
        verify(jsonpart, r, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithMoreData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"application/json\"}"
                + "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);

        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "application/json");
        verify(jsonpart, r, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithTextData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"text/plain\"}"
                + "This is just a plain text";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);

        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/plain");
        verify(jsonpart, r, expectedHeaders, "This is just a plain text");
    }

    @Test
    public void testParsingWithTextDataWithWhitespaces() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"text/plain\"}\n"
                + " This is just a plain text";
        Reader r = new StringReader(data);

        JSONObject jsonpart = SimpleRestInterceptor.parseJsonPart(r);

        Map<String, Object> expectedHeaders = new HashMap<String, Object>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/plain");
        verify(jsonpart, r, expectedHeaders, "\n This is just a plain text");
    }

    private void verify(JSONObject headers, Reader body, Map<String, Object> expectedHeaders, String expectedBody) {
        assertEquals(expectedHeaders.size(), headers.length());
        for (String key : expectedHeaders.keySet()) {
            assertEquals(headers.get(key), expectedHeaders.get(key), "value of key " + key + " differs");
        }
        assertEquals(extractContent(body), expectedBody);
    }

    private String extractContent(Reader reader) {
        char[] cbuf = new char[512];
        StringBuilder sb = new StringBuilder();
        int n;
        try {
            while ((n = reader.read(cbuf, 0, cbuf.length)) != -1) {
                sb.append(cbuf, 0,  n);
            }
        } catch (IOException e) {
            // ignore
        }
        return sb.toString();
    }

    private void setupAtmosphere() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return Mockito.mock(ServletContext.class);
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
        config = framework.getAtmosphereConfig();
    }

    private AtmosphereResource createAtmosphereResource(String method, String path, Map<String, String> headers, String data) {
        AtmosphereRequest.Builder b = new AtmosphereRequestImpl.Builder();
        AtmosphereRequest request = b.method("POST").pathInfo(path).headers(headers).body(data).build();
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance(request);
        response.request(request);
        AtmosphereResourceImpl resource = new AtmosphereResourceImpl();
        resource.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, Mockito.mock(AsyncSupport.class), null);
        return resource;
    }
}
