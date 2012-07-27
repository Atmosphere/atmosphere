/*
 * Copyright 2012 Jean-Francois Arcand
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
        AtmosphereResponse res = AtmosphereResponse.create().request(AtmosphereRequest.create());
        res.request().setAttribute(PROPERTY_USE_STREAM, false);
        res.asyncIOWriter(new AtmosphereInterceptorWriter(res).interceptor(new AsyncIOInterceptor() {
            @Override
            public void intercept(AtmosphereResponse response, String data) {
                s.set(data);
            }

            @Override
            public void intercept(AtmosphereResponse response, byte[] data) {
            }

            @Override
            public void intercept(AtmosphereResponse response, byte[] data, int offset, int length) {
            }
        })).write("test");
        assertEquals(s.get(), "test");
    }

    @Test
    public void chaining() throws ServletException, IOException {
        final AtomicReference<StringBuffer> s = new AtomicReference<StringBuffer>(new StringBuffer());
        AtmosphereResponse res = AtmosphereResponse.create().request(AtmosphereRequest.create());
        res.request().setAttribute(PROPERTY_USE_STREAM, false);
        res.asyncIOWriter(new AtmosphereInterceptorWriter(res).interceptor(new AsyncIOInterceptor() {
            @Override
            public void intercept(AtmosphereResponse response, String data) {
                s.get().append(data);
            }

            @Override
            public void intercept(AtmosphereResponse response, byte[] data) {
            }

            @Override
            public void intercept(AtmosphereResponse response, byte[] data, int offset, int length) {
            }
        }).interceptor(new AsyncIOInterceptor() {
            @Override
            public void intercept(AtmosphereResponse response, String data) {
                s.get().append("-yoyo");
            }

            @Override
            public void intercept(AtmosphereResponse response, byte[] data) {
            }

            @Override
            public void intercept(AtmosphereResponse response, byte[] data, int offset, int length) {
            }
        })).write("test");
        assertEquals(s.get().toString(), "test-yoyo");
    }

}
