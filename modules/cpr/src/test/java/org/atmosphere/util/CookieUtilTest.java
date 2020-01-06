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
package org.atmosphere.util;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.Cookie;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class CookieUtilTest {
    private static final String[] COOKIES1 = {"theme=light; sessionToken=abc123", "name=atmosphere"};

    @Test
    public void testDecodeServerCookies() throws Exception {
        Set<Cookie> cookies = new HashSet<Cookie>();
        for (String cookieHeader : COOKIES1) {
            CookieUtil.ServerCookieDecoder.STRICT.decode(cookieHeader, cookies);
        }
        
        assertEquals(cookies.size(), 3);
        for (Cookie cookie : cookies) {
            if ("theme".equals(cookie.getName())) {
                assertEquals(cookie.getValue(), "light");
            } else if ("sessionToken".equals(cookie.getName())) {
                assertEquals(cookie.getValue(), "abc123");
            } else if ("name".equals(cookie.getName())) {
                assertEquals(cookie.getValue(), "atmosphere");
            }
        }
    }
}
