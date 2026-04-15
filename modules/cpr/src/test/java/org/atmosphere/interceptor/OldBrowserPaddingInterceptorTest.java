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
package org.atmosphere.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OldBrowserPaddingInterceptorTest {

    @Test
    void paddingSizeIs8192PlusNewline() {
        String padding = PaddingAtmosphereInterceptor.confPadding(8192);
        // 8192 spaces + 1 newline
        assertEquals(8193, padding.length());
        assertTrue(padding.endsWith("\n"));
    }

    @Test
    void extendsPaddingAtmosphereInterceptor() {
        OldBrowserPaddingInterceptor interceptor = new OldBrowserPaddingInterceptor();
        assertTrue(interceptor instanceof PaddingAtmosphereInterceptor);
    }

    @Test
    void toStringReturnsExpected() {
        OldBrowserPaddingInterceptor interceptor = new OldBrowserPaddingInterceptor();
        assertEquals("Browser Padding Interceptor Support", interceptor.toString());
    }
}
