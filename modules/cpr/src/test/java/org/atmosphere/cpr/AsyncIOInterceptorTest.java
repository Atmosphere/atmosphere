/*
 * Copyright 2015 Jean-Francois Arcand
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

import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.testng.Assert.assertEquals;

public class AsyncIOInterceptorTest {

    @Test
    public void singleTest() throws ServletException, IOException {
        final AtomicReference<String> s = new AtomicReference<String>();
        AtmosphereResponse res = AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance());
        res.request().setAttribute(PROPERTY_USE_STREAM, false);
        res.asyncIOWriter(new AtmosphereInterceptorWriter().interceptor(new AsyncIOInterceptor() {

            @Override
            public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            }

            @Override
            public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                s.set(new String(data));
                return responseDraft;
            }

            @Override
            public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            }

            @Override
            public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
                return new byte[0];
            }

            @Override
            public void redirect(AtmosphereResponse response, String location) {
            }
        })).write("test");
        assertEquals(s.get(), "test");
    }

    @Test
    public void chaining() throws ServletException, IOException {
        final AtomicReference<StringBuffer> s = new AtomicReference<StringBuffer>(new StringBuffer());
        AtmosphereResponse res = AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance());
        res.request().setAttribute(PROPERTY_USE_STREAM, false);
        res.asyncIOWriter(new AtmosphereInterceptorWriter().interceptor(new AsyncIOInterceptor() {

            @Override
            public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            }

            @Override
            public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                return responseDraft;
            }

            @Override
            public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            }

            @Override
            public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
                return new byte[0];
            }

            @Override
            public void redirect(AtmosphereResponse response, String location) {
            }
        }).interceptor(new AsyncIOInterceptor() {

            @Override
            public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            }

            @Override
            public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                s.get().append(new String(responseDraft) + "-yoyo");
                return responseDraft;
            }

            @Override
            public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            }

            @Override
            public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
                return new byte[0];
            }

            @Override
            public void redirect(AtmosphereResponse response, String location) {
            }
        })).write("test");
        assertEquals(s.get().toString(), "test-yoyo");
    }

}
