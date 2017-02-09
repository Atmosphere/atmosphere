/*
 * Copyright 2017 Jean-Francois Arcand
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
package org.atmosphere.runtime;

import org.testng.annotations.Test;

import java.io.IOException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class AtmosphereInterceptorWriterTest {

    @Test
    public void addInterceptors() {
        AtmosphereInterceptorWriter writer = new AtmosphereInterceptorWriter();
        TestFilter[] filters = new TestFilter[4];
        for (int i = 0; i < 4; i++) {
            filters[i] = new TestFilter();
        }
        
        // adding the filters using the default logic (adding at the end of the list)
        writer.interceptor(filters[0]);
        writer.interceptor(filters[1]);
        verifyInterceptors(writer, new AsyncIOInterceptor[]{filters[0], filters[1]});
        
        // addint one at the beginning
        writer.interceptor(filters[2], 0);
        verifyInterceptors(writer, new AsyncIOInterceptor[]{filters[2], filters[0], filters[1]});

        // adding one at some position
        writer.interceptor(filters[3], 2);
        verifyInterceptors(writer, new AsyncIOInterceptor[]{filters[2], filters[0], filters[3], filters[1]});

        // adding the previously added one, ignored
        writer.interceptor(filters[2], 2);
        verifyInterceptors(writer, new AsyncIOInterceptor[]{filters[2], filters[0], filters[3], filters[1]});
    }

    private void verifyInterceptors(AtmosphereInterceptorWriter writer, AsyncIOInterceptor[] filters) {
        int p = 0;
        for (AsyncIOInterceptor f : writer.filters) {
            if (!filters[p].equals(f)) {
                fail("filter at index " + p + " does not match");
            }
            p++;
        }

        assertEquals(filters.length, p, "the wrong size of the list");

        p = filters.length;
        for (AsyncIOInterceptor f : writer.reversedFilters) {
            --p;
            if (!filters[p].equals(f)) {
                fail("reversedFilter index at " + p + " does not match");
            }
        }

        assertEquals(filters.length, filters.length - p, "the wrong size of the list");
    }

    private class TestFilter implements AsyncIOInterceptor {
        @Override
        public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            // noop;
        }
        
        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
            // noop
            return null;
        }

        @Override
        public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
            // noop
        }

        @Override
        public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
            // noop
            return null;
        }

        @Override
        public void redirect(AtmosphereResponse response, String location) {
            // noop
        }
    }
}
