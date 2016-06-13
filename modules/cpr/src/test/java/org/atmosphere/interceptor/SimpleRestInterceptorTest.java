/*
 * Copyright 2016 Async-IO.org
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

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class SimpleRestInterceptorTest {

    //TODO tests other part of the inteceptors

    @Test
    public void testParsingNoData() throws Exception {
        final String data = "{\"id\": \"123\", \"accept\" : \"text/plain\" }";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("accept", "text/plain");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataApos() throws Exception {
        final String data = "{'id': \"123\", 'accept' : 'text/plain' }";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("accept", "text/plain");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataAposMixedSpace() throws Exception {
        final String data = "{\n 'id':\"123\",'accept'\n: 'text/plain'\r }";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("accept", "text/plain");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataNumber() throws Exception {
        final String data = "{'id': \"123\", \"size\" : 69124, 'ack' : true }";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);
        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("size", "69124");
        expectedHeaders.put("ack", "true");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingNoDataNumberMixedSpace() throws Exception {
        final String data = "{'id': \"123\", \"size\":69124, \r\n'ack' :true }";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);
        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("size", "69124");
        expectedHeaders.put("ack", "true");
        verify(jer, expectedHeaders, null);
    }

    @Test
    public void testParsingWithData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"application/json\", \"data\": {\"records\": [{\"value\": \"S2Fma2E=\"}]}}";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "application/json");
        verify(jer, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithMoreData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"application/json\", "
                + "\"data\": {\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}}";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "application/json");
        verify(jer, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithDetachedDataWithLF() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"application/json\", \"detached\": true}\n"
                + "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "application/json");
        verify(jer, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithDetachedDataWithCRLF() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"application/json\", \"detached\": true}\r\n"
                + "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "application/json");
        verify(jer, expectedHeaders, "{\"records\": [{\"value\": \"S2Fma2E=\"}, {\"value\": \"S2Fma2E=\"},{\"value\": \"S2Fma2E=\"}]}");
    }

    @Test
    public void testParsingWithDetachedTextData() throws Exception {
        final String data = "{\"id\": \"123\", \"type\" : \"text/plain\", \"detached\": true}\n"
                + "This is just a plain text";
        Reader r = new StringReader(data);

        SimpleRestInterceptor.JSONEnvelopeReader jer = new SimpleRestInterceptor.JSONEnvelopeReader(r);

        Map<String, String> expectedHeaders = new HashMap<String, String>();
        expectedHeaders.put("id", "123");
        expectedHeaders.put("type", "text/plain");
        verify(jer, expectedHeaders, "This is just a plain text");
    }

    private void verify(SimpleRestInterceptor.JSONEnvelopeReader jer, Map<String, String> expectedHeaders, String expectedBody) {
        Map<String, String> headers = jer.getHeaders();
        assertEquals(expectedHeaders.size(), headers.size());
        for (String key : expectedHeaders.keySet()) {
            assertEquals(headers.get(key), expectedHeaders.get(key), "value of key " + key + " differs");
        }
        if (expectedBody == null) {
            assertNull(jer.getReader());
        } else {
            assertEquals(extractContent(jer.getReader()), expectedBody);
        }
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
        return sb.toString().trim();
    }

    private static class CoderHolder extends SimpleRestInterceptor {

    }
}
